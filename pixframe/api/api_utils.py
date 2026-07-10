"""Utility functions for views."""
import hashlib
import uuid
import flask
import pixframe


def verify_username_password(connection, username, password):
    """Verify username and password against the database."""
    row = connection.execute(
        "SELECT password FROM users WHERE username = ?",
        (username,)
    ).fetchone()

    if not row:
        return False

    # Verify password
    _, salt, stored_hash = row["password"].split('$')
    _, computed_hash = hash_password(password, salt)

    if stored_hash != computed_hash:
        return False
    return True


def hash_password(password, salt=None):
    """Hash a password with sha512 algorithm.

    If salt is provided, use it (for verification).
    If salt is None, generate a new salt (for new passwords).
    Returns tuple: (password_db_string, password_hash)
    """
    algorithm = 'sha512'
    if salt is None:
        salt = uuid.uuid4().hex
    hash_obj = hashlib.new(algorithm)
    hash_obj.update((salt + password).encode('utf-8'))
    password_hash = hash_obj.hexdigest()
    password_db_string = f"{algorithm}${salt}${password_hash}"
    return password_db_string, password_hash


def check_auth():
    """Authenticate user via session or HTTP Basic Auth.

    Returns logname if authenticated, None otherwise.
    """
    if 'username' in flask.session:
        return flask.session['username']
    if flask.request.authorization:
        username = flask.request.authorization['username']
        password = flask.request.authorization['password']
        if username and password:
            connection = pixframe.model.get_db()
            if verify_username_password(connection, username, password):
                return username
    return None


def error_403():
    """Return a 403 Forbidden error."""
    context = {
        "message": "Forbidden",
        "status_code": 403
    }
    return flask.jsonify(**context), 403


def error_400():
    """Return a 400 Bad Request error."""
    context = {
        "message": "Bad Request",
        "status_code": 400
    }
    return flask.jsonify(**context), 400


def error_404():
    """Return a 404 Not Found error."""
    context = {
        "message": "Not Found",
        "status_code": 404
    }
    return flask.jsonify(**context), 404


def error_409():
    """Return a 409 Conflict error."""
    context = {
        "message": "Conflict",
        "status_code": 409
    }
    return flask.jsonify(**context), 409
