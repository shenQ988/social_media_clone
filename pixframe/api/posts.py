"""REST API for posts."""
import os
import flask
import pixframe
from pixframe.api import api_utils
from pixframe.views.utils import save_uploaded_file


@pixframe.app.route('/api/v1/')
def get_all_api():
    """Return a list of all API resource URLs."""
    context = {
        "comments": "/api/v1/comments/",
        "likes": "/api/v1/likes/",
        "posts": "/api/v1/posts/",
        "url": "/api/v1/"
    }
    return flask.jsonify(**context)


@pixframe.app.route('/api/v1/posts/', methods=["GET"])
def get_post():
    """Return a list of posts matching query parameters."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    connection = pixframe.model.get_db()

    page_size = flask.request.args.get("size", 10, type=int)
    page_num = flask.request.args.get("page", 0, type=int)
    offset = page_size * page_num
    postid_lte = flask.request.args.get("postid_lte", type=int)
    # if the page number or page size is negative return status code 400,
    # means bad request
    if page_num < 0 or page_size < 0:
        return api_utils.error_400()

    base_query = (
        "SELECT postid  FROM posts "
        "WHERE (posts.owner = ? or "
        "posts.owner in "
        "(SELECT followee FROM following "
        "WHERE following.follower = ?)) "
    )

    arguments = [logname, logname]

    if postid_lte is not None:
        base_query += "AND postid <= ? "
        arguments.append(postid_lte)

    base_query += (
        "ORDER BY postid DESC "
        "LIMIT ? OFFSET ?"
    )
    arguments.append(page_size)
    arguments.append(offset)

    posts = connection.execute(base_query, arguments).fetchall()

    post_info = []
    for p in posts:
        post_info.append({
            "postid": p["postid"],
            "url": f"/api/v1/posts/{p['postid']}/"
        })

    next_url = ""
    if len(posts) < page_size:
        next_url = ""
    else:
        if postid_lte is None:
            next_lte = posts[0]["postid"]
        else:
            next_lte = postid_lte

        next_url = (
            f"/api/v1/posts/?size={page_size}"
            f"&page={page_num + 1}&postid_lte={next_lte}"
        )

    # get the current request url, annoying because
    # flask.request.full_path will add ? for those url without the ?
    current_url = flask.request.full_path
    if current_url.endswith('?'):
        current_url = current_url[:-1]

    context = {
        "next": next_url,
        "results": post_info,
        "url": current_url
    }

    return flask.jsonify(**context)


@pixframe.app.route('/api/v1/posts/<int:postid>/', methods=["GET"])
def get_post_details(postid):
    """Return details for a single post."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    connection = pixframe.model.get_db()

    # get post data
    post = connection.execute(
        "SELECT p.filename AS imageUrl, "
        "p.owner, p.created, u.filename AS owner_img_url "
        "FROM posts p JOIN users u ON p.owner = u.username "
        "WHERE p.postid = ?",
        (postid,)
    ).fetchone()

    if not post:
        return api_utils.error_404()

    comments = connection.execute(
        "SELECT commentid, owner, text "
        "FROM comments c "
        "WHERE c.postid = ? "
        "ORDER BY commentid ASC",
        (postid,)
    ).fetchall()

    comments_list = []
    for comment in comments:
        comments_list.append({
            "commentid": comment["commentid"],
            "lognameOwnsThis": (comment["owner"] == logname),
            "owner": comment["owner"],
            "ownerShowUrl": f"/users/{comment['owner']}/",
            "text": comment["text"],
            "url": f"/api/v1/comments/{comment['commentid']}/"
        })

    likes_count = connection.execute(
        "SELECT COUNT(*) AS num_likes "
        "FROM likes "
        "WHERE postid = ?",
        (postid,)
    ).fetchone()

    num_likes = likes_count["num_likes"]

    like_row = connection.execute(
        "SELECT likeid FROM likes WHERE owner = ? AND postid = ?",
        (logname, postid)
    ).fetchone()

    if like_row is not None:
        logname_likes_this = True
        like_url = f"/api/v1/likes/{like_row['likeid']}/"
    else:
        logname_likes_this = False
        like_url = None

    like_object = {
        "lognameLikesThis": logname_likes_this,
        "numLikes": num_likes,
        "url": like_url,
    }

    context = {
        "comments": comments_list,
        "comments_url": f"/api/v1/comments/?postid={postid}",
        "created": post["created"],
        "imgUrl": f"/uploads/{post['imageUrl']}",
        "likes": like_object,
        "owner": post["owner"],
        "ownerImgUrl": f"/uploads/{post['owner_img_url']}",
        "ownerShowUrl": f"/users/{post['owner']}/",
        "postShowUrl": f"/posts/{postid}/",
        "postid": postid,
        "url": flask.request.path,
    }

    return flask.jsonify(**context)


@pixframe.app.route('/api/v1/posts/', methods=["POST"])
def create_post():
    """Create a post from a multipart form containing a 'file'."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    file_obj = flask.request.files.get("file")
    if not file_obj or not file_obj.filename:
        return api_utils.error_400()

    uuid_basename = save_uploaded_file(file_obj)

    connection = pixframe.model.get_db()
    connection.execute(
        "INSERT INTO posts (filename, owner) VALUES (?, ?)",
        (uuid_basename, logname)
    )
    postid = connection.execute(
        "SELECT last_insert_rowid()"
    ).fetchone()["last_insert_rowid()"]

    context = {
        "postid": postid,
        "url": f"/api/v1/posts/{postid}/",
    }
    return flask.jsonify(**context), 201


@pixframe.app.route('/api/v1/posts/<int:postid>/', methods=["DELETE"])
def delete_post(postid):
    """Delete a post owned by the logged-in user."""
    logname = api_utils.check_auth()
    if logname is None:
        return api_utils.error_403()

    connection = pixframe.model.get_db()

    row = connection.execute(
        "SELECT filename, owner FROM posts WHERE postid = ?",
        (postid,)
    ).fetchone()

    if not row:
        return api_utils.error_404()

    if row['owner'] != logname:
        return api_utils.error_403()

    path_to_post_file = pixframe.app.config["UPLOAD_FOLDER"] / row['filename']
    if os.path.exists(path_to_post_file):
        os.remove(path_to_post_file)

    connection.execute("DELETE FROM posts WHERE postid = ?", (postid,))
    return "", 204
