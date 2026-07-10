"""Pixframe REST API for user profiles and the explore/discovery list."""
import flask
import pixframe
from pixframe.api import api_utils


@pixframe.app.route('/api/v1/users/<username>/', methods=["GET"])
def api_show_user(username):
    """Return a user's profile: fullname, counts, follow status, posts."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    connection = pixframe.model.get_db()

    row = connection.execute(
        "SELECT fullname, filename FROM users WHERE username = ?",
        (username,)
    ).fetchone()
    if not row:
        return api_utils.error_404()

    logname_follows_username = connection.execute(
        "SELECT 1 FROM following WHERE follower = ? AND followee = ?",
        (logname, username)
    ).fetchone() is not None

    total_posts = connection.execute(
        "SELECT COUNT(*) AS cnt FROM posts WHERE owner = ?",
        (username,)
    ).fetchone()["cnt"]

    followers = connection.execute(
        "SELECT COUNT(*) AS cnt FROM following WHERE followee = ?",
        (username,)
    ).fetchone()["cnt"]

    following = connection.execute(
        "SELECT COUNT(*) AS cnt FROM following WHERE follower = ?",
        (username,)
    ).fetchone()["cnt"]

    posts = connection.execute(
        "SELECT postid, filename "
        "FROM posts WHERE owner = ? ORDER BY postid DESC",
        (username,)
    ).fetchall()

    posts_list = [{
        "postid": post["postid"],
        "imgUrl": f"/uploads/{post['filename']}",
        "url": f"/posts/{post['postid']}/",
    } for post in posts]

    return flask.jsonify(
        username=username,
        fullname=row["fullname"],
        user_img_url=f"/uploads/{row['filename']}",
        logname=logname,
        logname_follows_username=logname_follows_username,
        followers=followers,
        following=following,
        total_posts=total_posts,
        posts=posts_list,
    )


@pixframe.app.route('/api/v1/users/<username>/followers/', methods=["GET"])
def api_show_followers(username):
    """Return the list of users following `username`."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    connection = pixframe.model.get_db()

    if not connection.execute(
        "SELECT 1 FROM users WHERE username = ?", (username,)
    ).fetchone():
        return api_utils.error_404()

    rows = connection.execute(
        "SELECT users.username, users.filename AS user_img_url "
        "FROM users "
        "JOIN following ON users.username = following.follower "
        "WHERE following.followee = ?",
        (username,)
    ).fetchall()

    followers = []
    for row in rows:
        is_following = connection.execute(
            "SELECT 1 FROM following WHERE follower = ? AND followee = ?",
            (logname, row["username"])
        ).fetchone() is not None
        followers.append({
            "username": row["username"],
            "user_img_url": f"/uploads/{row['user_img_url']}",
            "logname_follows_username": is_following,
        })

    return flask.jsonify(followers=followers)


@pixframe.app.route('/api/v1/users/<username>/following/', methods=["GET"])
def api_show_following(username):
    """Return the list of users `username` follows."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    connection = pixframe.model.get_db()

    if not connection.execute(
        "SELECT 1 FROM users WHERE username = ?", (username,)
    ).fetchone():
        return api_utils.error_404()

    rows = connection.execute(
        "SELECT users.username, users.filename AS user_img_url "
        "FROM users "
        "JOIN following ON users.username = following.followee "
        "WHERE following.follower = ?",
        (username,)
    ).fetchall()

    following = []
    for row in rows:
        is_following = connection.execute(
            "SELECT 1 FROM following WHERE follower = ? AND followee = ?",
            (logname, row["username"])
        ).fetchone() is not None
        following.append({
            "username": row["username"],
            "user_img_url": f"/uploads/{row['user_img_url']}",
            "logname_follows_username": is_following,
        })

    return flask.jsonify(following=following)


@pixframe.app.route('/api/v1/explore/', methods=["GET"])
def api_explore():
    """Return users the logged-in user is not already following."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    connection = pixframe.model.get_db()

    rows = connection.execute(
        "SELECT username, filename "
        "FROM users "
        "WHERE username != ? "
        "AND username NOT IN ("
        "SELECT followee FROM following WHERE follower = ?"
        ")",
        (logname, logname)
    ).fetchall()

    results = [{
        "username": row["username"],
        "user_img_url": f"/uploads/{row['filename']}",
    } for row in rows]

    return flask.jsonify(results=results)
