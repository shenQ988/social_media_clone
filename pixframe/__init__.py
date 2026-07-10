"""Pixframe package initializer."""
import flask
import flask_wtf.csrf

# app is a single object used by all the code modules in this package
app = flask.Flask(__name__)  # pylint: disable=invalid-name

# Read settings from config module (pixframe/config.py)
app.config.from_object('pixframe.config')

# Overlay settings read from a Python file whose path is set in the environment
# variable PIXFRAME_SETTINGS. Setting this environment variable is optional.
# Docs: http://flask.pocoo.org/docs/latest/config/
#
# EXAMPLE:
# $ export PIXFRAME_SETTINGS=secret_key_config.py
app.config.from_envvar('PIXFRAME_SETTINGS', silent=True)

flask_wtf.csrf.CSRFProtect(app)


@app.after_request
def _add_query_count_header(response):
    """Expose the per-request SQL query count when QUERY_DEBUG is on."""
    if app.config.get('QUERY_DEBUG'):
        response.headers['X-DB-Query-Count'] = str(
            flask.g.get('query_count', 0)
        )
    return response

# Tell our app about views and model.  This is dangerously close to a
# circular import, which is naughty, but Flask was designed that way.
# (Reference http://flask.pocoo.org/docs/patterns/packages/)  We're
# going to tell pylint and pycodestyle to ignore this coding style violation.
import pixframe.api  # noqa: E402  pylint: disable=wrong-import-position
import pixframe.views  # noqa: E402  pylint: disable=wrong-import-position
import pixframe.model  # noqa: E402  pylint: disable=wrong-import-position
