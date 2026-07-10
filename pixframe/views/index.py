"""
Pixframe file serving and SPA shell routes.

URLs include:
/
/uploads/<filename>
/<any other client-routed path>
"""
import os
import flask
import pixframe


@pixframe.app.route('/uploads/<path:filename>')
def download_file(filename):
    """Serve a file from the upload directory."""
    if 'username' not in flask.session:
        flask.abort(403)

    path = pixframe.app.config['UPLOAD_FOLDER'] / filename
    if not os.path.exists(path):
        flask.abort(404)

    return flask.send_from_directory(
        str(pixframe.app.config['UPLOAD_FOLDER']),
        filename,
        as_attachment=False
    )


@pixframe.app.route('/', defaults={'path': ''})
@pixframe.app.route('/<path:path>')
def spa_shell(path):
    """Serve the React SPA shell for any non-API, non-upload route.

    React Router owns client-side navigation from here; this route only
    needs to exist so a hard refresh (or a shared link) on a deep route
    like /users/alice/followers/ still returns a 200 with the app shell
    instead of a Flask 404.
    """
    # pylint: disable=unused-argument
    return flask.render_template("index.html")
