from typing import Any
from urllib.parse import quote_plus

from google.adk.sessions.database_session_service import Base, DatabaseSessionService
from sqlalchemy import text
from sqlalchemy.engine import create_engine

from adkpoc.utils.env import get_env
SCHEMA_NAME = "adk"


def _get_postgres_url() -> str:
    host = get_env("DB_HOST","localhost")
    port = get_env("DB_PORT", "5432")
    database = get_env("DB_NAME","agent_dev")
    user = get_env("DB_USER","postgres")
    password = get_env("DB_PASSWORD","mysecretpassword")
    # host = "localhost"
    # port = "5432"
    # database = "agent_dev"
    # user = "postgres"
    # password = "mysecretpassword"

    user_enc = quote_plus(user or "")
    if password is None:
        credentials = user_enc
    else:
        credentials = f"{user_enc}:{quote_plus(password)}"
    return f"postgresql+psycopg2://{credentials}@{host}:{port}/{database}"


class ADKSessionService(DatabaseSessionService):
    """Session service configured to use the adk_sessions schema in Postgres."""

    def __init__(self, db_url: str, **kwargs: Any):
        for table in Base.metadata.tables.values():
            table.schema = SCHEMA_NAME

        bootstrap_engine = create_engine(db_url, **kwargs)
        try:
            with bootstrap_engine.connect() as connection:
                connection.execute(text(f'CREATE SCHEMA IF NOT EXISTS "{SCHEMA_NAME}"'))
                connection.commit()
        finally:
            bootstrap_engine.dispose()

        super().__init__(db_url=db_url, **kwargs)


session_service = ADKSessionService(db_url=_get_postgres_url())
