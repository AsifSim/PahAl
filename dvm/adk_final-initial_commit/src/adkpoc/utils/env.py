
import os
from typing import Optional

def get_env(key: str, default: Optional[str] = None) -> str:
    """
    Gets an environment variable.

    Args:
        key: The name of the environment variable.
        default: The default value to return if the environment variable is not set.

    Returns:
        The value of the environment variable.

    Raises:
        ValueError: If the environment variable is not set and no default value is provided.
    """
    value = os.environ.get(key)
    if value is not None:
        return value
    if default is not None:
        return default
    raise ValueError(f"Environment variable {key} not set")
