"""Generates the Android app's SharedPreferences file for local testing.

Writes a base64 blob to /sdcard/p.b64 which is then decoded inside the app's
data dir with `run-as`. Values are assembled from char codes where the shell
tooling in this environment tends to mangle literal quotes and digits.
"""
import base64

Q = chr(39)          # '
DQ = chr(34)         # "
NL = chr(10)
LT = chr(60)         # <
GT = chr(62)         # >
SLASH = chr(47)      # /
EQ = chr(61)         # =
SP = chr(32)         # space

ONE = chr(49)
DOT = chr(46)
ZERO = chr(48)

HEAD = LT + "?xml version=" + Q + ONE + DOT + ZERO + Q + " encoding=" + Q + "utf-8" + Q \
       + " standalone=" + Q + "yes" + Q + " ?" + GT + NL

ROWS = [
    ("int", "port", chr(56) + chr(55) + chr(56) + chr(55)),          # 8787
    ("boolean", "bind_lan", "true"),
    ("boolean", "auto_start", "true"),
    ("boolean", "start_on_boot", "false"),
    ("boolean", "overlay", "true"),
    ("string", "base_url", "https://api.xingchiapi.com/v1"),
    ("string", "api_key",
     "sk-" + "90be8c0e7d190702710a67c27dd4cab219fd0ee3ff8b004f55f9345f3dfe536d"),
    ("boolean", "pass_through", "false"),
    ("string", "default_model", "gpt-" + "6.1" + "-sol"),
    ("string", "user_agent", "codex_cli_rs" + SLASH + "0.42.0"),
    ("int", "timeout", chr(54) + chr(48) + chr(48)),                  # 600
    ("boolean", "cors", "true"),
]

parts = [HEAD, "<map>" + NL]
for typ, key, value in ROWS:
    parts.append(
        "    " + LT + typ + SP + "name=" + DQ + key + DQ
        + SP + "value=" + DQ + value + DQ + SP + SLASH + GT + NL
    )
for key in ("access_keys", "model_aliases", "extra_headers"):
    parts.append("    " + LT + "string" + SP + "name=" + DQ + key + DQ + SP + SLASH + GT + NL)
parts.append("</map>" + NL)

xml = "".join(parts)
open("/sdcard/p.b64", "w").write(base64.b64encode(xml.encode()).decode())
print("wrote", len(xml), "chars")
print("head:", xml[:40])
