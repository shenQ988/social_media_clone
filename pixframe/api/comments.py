"""Pixframe REST API for comments."""
import flask
import pixframe
from pixframe.api import api_utils


# POST /api/v1/comments/?postid=<postid>
@pixframe.app.route("/api/v1/comments/", methods=["POST"])
def add_comment():
    """Create a new comment on a post."""
    logname = api_utils.check_auth()
    if not logname:
        return api_utils.error_403()

    connection = pixframe.model.get_db()

    # get the postid
    postid = flask.request.args.get("postid", type=int)
    if not postid:
        return api_utils.error_400()

    # Get the text from JSON body
    text = flask.request.json.get("text")
    if not text:
        return api_utils.error_400()

    # Check if the post exists
    post = connection.execute(
        "SELECT postid FROM posts WHERE postid = ?",
        (postid,)
    ).fetchone()

    if not post:
        return api_utils.error_404()

    # Add the comment
    connection.execute(
        "INSERT INTO comments (owner, postid, text) VALUES (?, ?, ?)",
        (logname, postid, text)
    )

    commentid = connection.execute(
        "SELECT last_insert_rowid()"
    ).fetchone()["last_insert_rowid()"]

    context = {
        "commentid": commentid,
        "lognameOwnsThis": True,
        "owner": logname,
        "ownerShowUrl": f"/users/{logname}/",
        "text": text,
        "url": f"/api/v1/comments/{commentid}/"
    }

    return flask.jsonify(**context), 201


@pixframe.app.route(
    "/api/v1/comments/<int:commentid>/", methods=["DELETE"]
)
def delete_comment(commentid):
    """Delete a comment by commentid."""
    logname = api_utils.check_auth()
    if not logname:
        return api_utils.error_403()

    connection = pixframe.model.get_db()

    # check if the comment exist as well as if user own it
    comment = connection.execute(
        "SELECT owner FROM comments "
        "WHERE commentid = ?",
        (commentid,)
    ).fetchone()

    if not comment:
        return api_utils.error_404()

    if comment["owner"] != logname:
        return api_utils.error_403()

    cur = connection.execute(
        "DELETE FROM comments "
        "WHERE commentid = ?",
        (commentid,)
    )
    if cur.rowcount == 0:
        return api_utils.error_404()

    return "", 204
