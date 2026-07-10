"""Utility functions for views."""
import uuid
import pathlib
import pixframe


def save_uploaded_file(file_obj):
    """Save an uploaded file with a UUID-based name.

    Returns the new filename (uuid_basename).
    """
    extension = pathlib.Path(file_obj.filename).suffix.lower()
    uuid_basename = f"{uuid.uuid4().hex}{extension}"
    path = pixframe.app.config["UPLOAD_FOLDER"] / uuid_basename
    file_obj.save(path)
    return uuid_basename
