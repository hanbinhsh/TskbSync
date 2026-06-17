"""Shared runtime configuration for the TskbSync backend.

Both main.py (the server) and tray.pyw (the controller) read/write the same
config.json so the tray can change the password / toggle encryption and the
server picks it up on the next restart.
"""

import json
import os
import secrets

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")
CERT_DIR = os.path.join(BASE_DIR, "certs")
CERT_PATH = os.path.join(CERT_DIR, "server.crt")
KEY_PATH = os.path.join(CERT_DIR, "server.key")

DEFAULTS = {
    "password": "",
    "use_tls": False,
}


def load_config():
    cfg = dict(DEFAULTS)
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            cfg.update(data)
    except Exception:
        pass
    return cfg


def save_config(cfg):
    tmp = CONFIG_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)
    os.replace(tmp, CONFIG_PATH)


def generate_password():
    # 6 hex chars: short enough to type on a phone, far better than a default.
    return secrets.token_hex(3)


def ensure_config():
    """Load config, generating a random password on first run."""
    cfg = load_config()
    changed = False
    if not cfg.get("password"):
        cfg["password"] = generate_password()
        changed = True
    if changed:
        save_config(cfg)
    return cfg


def set_password(password):
    cfg = load_config()
    cfg["password"] = password
    save_config(cfg)
    return cfg


def set_use_tls(enabled):
    cfg = load_config()
    cfg["use_tls"] = bool(enabled)
    save_config(cfg)
    return cfg


def ensure_certificate():
    """Generate a self-signed certificate if one does not exist.

    Returns (cert_path, key_path) on success, or None if generation failed
    (e.g. the `cryptography` package is unavailable).
    """
    if os.path.exists(CERT_PATH) and os.path.exists(KEY_PATH):
        return CERT_PATH, KEY_PATH
    try:
        import datetime
        import ipaddress

        from cryptography import x509
        from cryptography.x509.oid import NameOID
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa

        os.makedirs(CERT_DIR, exist_ok=True)
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "TskbSync")])
        now = datetime.datetime.utcnow()
        cert = (
            x509.CertificateBuilder()
            .subject_name(name)
            .issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .add_extension(
                x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("0.0.0.0"))]),
                critical=False,
            )
            .sign(key, hashes.SHA256())
        )
        with open(KEY_PATH, "wb") as f:
            f.write(
                key.private_bytes(
                    encoding=serialization.Encoding.PEM,
                    format=serialization.PrivateFormat.TraditionalOpenSSL,
                    encryption_algorithm=serialization.NoEncryption(),
                )
            )
        with open(CERT_PATH, "wb") as f:
            f.write(cert.public_bytes(serialization.Encoding.PEM))
        return CERT_PATH, KEY_PATH
    except Exception:
        return None
