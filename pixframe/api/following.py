"""Pixframe REST API for follow/unfollow relationships."""
import flask
import pixframe
from pixframe.api import api_utils


@pixframe.app.route("/api/v1/following/", methods=["POST"])
def api_follow():
    """Follow a user given a JSON {username} body."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    body = flask.request.get_json(silent=True) or {}
    username = body.get("username")
    if not username:
        return api_utils.error_400()

    connection = pixframe.model.get_db()

    if not connection.execute(
        "SELECT 1 FROM users WHERE username = ?", (username,)
    ).fetchone():
        return api_utils.error_404()

    already_following = connection.execute(
        "SELECT 1 FROM following WHERE follower = ? AND followee = ?",
        (logname, username)
    ).fetchone() is not None

    if already_following:
        return api_utils.error_409()

    connection.execute(
        "INSERT INTO following (follower, followee) VALUES (?, ?)",
        (logname, username)
    )
    return flask.jsonify(follower=logname, followee=username), 201


@pixframe.app.route("/api/v1/following/<username>/", methods=["DELETE"])
def api_unfollow(username):
    """Unfollow a user."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    connection = pixframe.model.get_db()

    already_following = connection.execute(
        "SELECT 1 FROM following WHERE follower = ? AND followee = ?",
        (logname, username)
    ).fetchone() is not None

    if not already_following:
        return api_utils.error_404()

    connection.execute(
        "DELETE FROM following WHERE follower = ? AND followee = ?",
        (logname, username)
    )
    return "", 204
