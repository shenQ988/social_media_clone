"""Pixframe development configuration."""

import os
import pathlib

# Root of this application, useful if it doesn't occupy an entire domain
APPLICATION_ROOT = '/'

# Secret key for encrypting cookies
SECRET_KEY = b'\xc2}Z\xb6\xa3a\x8b\x94/;\xeb\x127\xec:\x9a\xf6_`\x02\x0f\xf7zd'
SESSION_COOKIE_NAME = 'login'

# File Upload to var/uploads/
PIXFRAME_ROOT = pathlib.Path(__file__).resolve().parent.parent
UPLOAD_FOLDER = PIXFRAME_ROOT/'var'/'uploads'
ALLOWED_EXTENSIONS = set(['png', 'jpg', 'jpeg', 'gif'])
MAX_CONTENT_LENGTH = 16 * 1024 * 1024

# Database file is var/pixframe.sqlite3
DATABASE_FILENAME = PIXFRAME_ROOT/'var'/'pixframe.sqlite3'

# Enable CSRF protection
WTF_CSRF_ENABLED = False

# Opt-in per-request SQL query counter, exposed via the X-DB-Query-Count
# response header. Off by default; enable with the PIXFRAME_QUERY_DEBUG=1
# environment variable for load testing / performance profiling.
QUERY_DEBUG = bool(int(os.environ.get('PIXFRAME_QUERY_DEBUG', '0')))
