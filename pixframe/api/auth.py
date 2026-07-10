"""Pixframe REST API for account authentication and management."""
import os
import flask
import pixframe
from pixframe.api import api_utils
from pixframe.api.api_utils import hash_password, verify_username_password
from pixframe.views.utils import save_uploaded_file


@pixframe.app.route("/api/v1/accounts/auth/", methods=["GET"])
def api_check_auth():
    """Return the logged-in user's username, or 403 if not authenticated."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()
    return flask.jsonify(logname=logname)


@pixframe.app.route("/api/v1/accounts/login/", methods=["POST"])
def api_login():
    """Log in with a JSON {username, password} body."""
    body = flask.request.get_json(silent=True) or {}
    username = body.get("username")
    password = body.get("password")
    if not username or not password:
        return api_utils.error_400()

    connection = pixframe.model.get_db()
    if not verify_username_password(connection, username, password):
        return api_utils.error_403()

    flask.session['username'] = username
    return flask.jsonify(logname=username)


@pixframe.app.route("/api/v1/accounts/", methods=["GET"])
def api_get_account():
    """Return the logged-in user's own account details."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    connection = pixframe.model.get_db()
    row = connection.execute(
        "SELECT fullname, email, filename FROM users WHERE username = ?",
        (logname,)
    ).fetchone()

    return flask.jsonify(
        username=logname,
        fullname=row["fullname"],
        email=row["email"],
        user_img_url=f"/uploads/{row['filename']}",
    )


@pixframe.app.route("/api/v1/accounts/logout/", methods=["POST"])
def api_logout():
    """Log out the current session."""
    flask.session.clear()
    return "", 204


@pixframe.app.route("/api/v1/accounts/", methods=["POST"])
def api_create_account():
    """Create a new account from a multipart form (fields + file)."""
    form = flask.request.form
    file_obj = flask.request.files.get("file")

    if not all([form.get("fullname"), form.get("username"),
                form.get("email"), form.get("password"),
                file_obj, file_obj.filename]):
        return api_utils.error_400()

    connection = pixframe.model.get_db()
    if connection.execute(
        "SELECT 1 FROM users WHERE username = ?",
        (form["username"],)
    ).fetchone():
        return api_utils.error_409()

    password_db_string, _ = hash_password(form["password"])
    uuid_basename = save_uploaded_file(file_obj)

    connection.execute(
        "INSERT INTO users (username, fullname, email, password, filename) "
        "VALUES (?, ?, ?, ?, ?)",
        (form["username"], form["fullname"], form["email"],
         password_db_string, uuid_basename)
    )

    flask.session['username'] = form["username"]
    return flask.jsonify(logname=form["username"]), 201


@pixframe.app.route("/api/v1/accounts/", methods=["DELETE"])
def api_delete_account():
    """Delete the logged-in user's account."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    connection = pixframe.model.get_db()

    user_row = connection.execute(
        "SELECT filename FROM users WHERE username = ?", (logname,)
    ).fetchone()

    path_to_pp = pixframe.app.config["UPLOAD_FOLDER"] / user_row['filename']
    if os.path.exists(path_to_pp):
        os.remove(path_to_pp)

    posts = connection.execute(
        "SELECT filename FROM posts WHERE owner = ?", (logname,)
    ).fetchall()
    for post in posts:
        post_pic = pixframe.app.config["UPLOAD_FOLDER"] / post["filename"]
        if os.path.exists(post_pic):
            os.remove(post_pic)

    connection.execute("DELETE FROM users WHERE username = ?", (logname,))
    flask.session.clear()
    return "", 204


@pixframe.app.route("/api/v1/accounts/", methods=["PATCH"])
def api_edit_account():
    """Edit the logged-in user's fullname/email/photo."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    fullname = flask.request.form.get('fullname')
    email = flask.request.form.get('email')
    file_obj = flask.request.files.get('file')

    if not fullname or not email:
        return api_utils.error_400()

    connection = pixframe.model.get_db()

    old_filename = None
    if file_obj and file_obj.filename:
        old_filename = connection.execute(
            "SELECT filename FROM users WHERE username = ?",
            (logname,)
        ).fetchone()['filename']

    connection.execute(
        "UPDATE users SET fullname = ?, email = ? WHERE username = ?",
        (fullname, email, logname)
    )

    filename = old_filename
    if file_obj and file_obj.filename:
        filename = save_uploaded_file(file_obj)
        if old_filename:
            old_path = pixframe.app.config["UPLOAD_FOLDER"] / old_filename
            if os.path.exists(old_path):
                os.remove(old_path)
        connection.execute(
            "UPDATE users SET filename = ? WHERE username = ?",
            (filename, logname)
        )

    return flask.jsonify(
        logname=logname, fullname=fullname, email=email, filename=filename
    )


@pixframe.app.route("/api/v1/accounts/password/", methods=["PUT"])
def api_update_password():
    """Change the logged-in user's password from a JSON body."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    body = flask.request.get_json(silent=True) or {}
    old_password = body.get('password')
    new_password_1 = body.get('new_password1')
    new_password_2 = body.get('new_password2')

    if not all([old_password, new_password_1, new_password_2]):
        return api_utils.error_400()

    connection = pixframe.model.get_db()
    stored_password = connection.execute(
        "SELECT password FROM users WHERE username = ?", (logname,)
    ).fetchone()['password']

    _, salt, stored_hash = stored_password.split('$')
    _, computed_hash = hash_password(old_password, salt)

    if stored_hash != computed_hash:
        return api_utils.error_403()

    if new_password_1 != new_password_2:
        return api_utils.error_400()

    password_db_string, _ = hash_password(new_password_1)
    connection.execute(
        "UPDATE users SET password = ? WHERE username = ?",
        (password_db_string, logname)
    )
    return "", 204
