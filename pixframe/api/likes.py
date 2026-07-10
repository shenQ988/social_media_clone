"""Pixframe REST API for likes."""
import flask
import pixframe
from pixframe.api import api_utils


@pixframe.app.route("/api/v1/likes/", methods=["POST"])
def create_like():
    """Create a like on a post."""
    logname = api_utils.check_auth()
    if not logname:
        return api_utils.error_403()

    postid = flask.request.args.get('postid', type=int)
    if not postid:
        return api_utils.error_400()

    connection = pixframe.model.get_db()

    # check if the post exist
    post = connection.execute(
        "SELECT postid "
        "FROM posts "
        "WHERE postid = ?",
        (postid,)
    ).fetchone()
    if not post:
        return api_utils.error_404()

    like_exist = connection.execute(
        "SELECT likeid "
        "FROM likes "
        "WHERE owner = ? AND postid = ?",
        (logname, postid)
    ).fetchone()

    if like_exist is not None:
        likeid = like_exist["likeid"]
        url = f"/api/v1/likes/{likeid}/"
        status_code = 200
    else:
        connection.execute(
            "INSERT INTO likes (owner, postid) VALUES (?, ?)",
            (logname, postid)
        )

        likeid = connection.execute(
            "SELECT last_insert_rowid()"
        ).fetchone()["last_insert_rowid()"]

        url = f"/api/v1/likes/{likeid}/"
        status_code = 201

    context = {
        "likeid": likeid,
        "url": url
    }

    return flask.jsonify(**context), status_code


@pixframe.app.route("/api/v1/likes/<int:likeid>/", methods=["DELETE"])
def delete_like(likeid):
    """Delete a like by likeid."""
    logname = api_utils.check_auth()
    if not logname:
        return api_utils.error_403()

    connection = pixframe.model.get_db()
    # Check if the like exists and retrieve its owner
    like = connection.execute(
        "SELECT owner FROM likes WHERE likeid = ?",
        (likeid,)
    ).fetchone()

    if not like:
        return api_utils.error_404()

    # If the user does not own the like, return 403
    if like["owner"] != logname:
        return api_utils.error_403()

    # Delete the like
    cur = connection.execute(
        "DELETE FROM likes WHERE likeid = ?",
        (likeid,)
    )
    if cur.rowcount == 0:
        return api_utils.error_404()

    return "", 204
