"""
Declarative base for every ORM model.

``Base`` lives in its own module so that model modules can import it without
going through :mod:`app.db.base`, which imports those same model modules to
populate ``Base.metadata``. Defining ``Base`` inside the import hub made
``import app.main`` fail with a circular-import ``ImportError``: whichever model
module was imported first hit ``app.db.base`` mid-initialization, and the hub's
own import of that model found it only partially initialized.
"""
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass
