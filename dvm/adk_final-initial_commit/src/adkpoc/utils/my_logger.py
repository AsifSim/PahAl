
import logging
import os
from rich.logging import RichHandler

LOGS_DIR = "logs"
if not os.path.exists(LOGS_DIR):
    os.makedirs(LOGS_DIR)

def get_log_config(log_level: str = "info") -> dict:
    """Returns a logging configuration dictionary for uvicorn."""
    return {
        "version": 1,
        "disable_existing_loggers": False,
        "formatters": {
            "default": {
                "()": "uvicorn.logging.DefaultFormatter",
                "fmt": "%(levelprefix)s %(message)s",
                "use_colors": None,
            },
            "access": {
                "()": "uvicorn.logging.AccessFormatter",
                "fmt": '%(levelprefix)s %(client_addr)s - "%(request_line)s" %(status_code)s',
            },
        },
        "handlers": {
            "default": {
                "formatter": "default",
                "class": "rich.logging.RichHandler",
            },
            "access": {
                "formatter": "access",
                "class": "rich.logging.RichHandler",
            },
        },
        "loggers": {
            "uvicorn": {"handlers": ["default"], "level": log_level.upper()},
            "uvicorn.error": {"level": log_level.upper()},
            "uvicorn.access": {"handlers": ["access"], "level": log_level.upper(), "propagate": False},
        },
    }

def get_logger(name: str) -> logging.Logger:
    """Get a logger that writes to a file."""
    file_handler = logging.FileHandler(os.path.join(LOGS_DIR, "adkpoc.log"))
    file_handler.setLevel(logging.DEBUG)
    formatter = logging.Formatter(
        '%(asctime)s | %(name)s | [%(module)s:%(lineno)d] | %(message)s',
        datefmt="[%X]",
    )
    file_handler.setFormatter(formatter)

    logging.basicConfig(
        level="DEBUG",
        format='%(asctime)s | %(name)s | [%(module)s:%(lineno)d] | %(message)s',
        datefmt="[%X]",
        handlers=[file_handler],
    )
    return logging.getLogger(name)