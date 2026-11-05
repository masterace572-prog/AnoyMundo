#!/usr/bin/env python3
"""
One-off migration helper: converts the original CodeIgniter PHP views into
Handlebars templates, keeping the HTML/CSS byte-for-byte where possible.

The generated .hbs files are committed, so this script is NOT needed at build
time. It is kept for traceability.

Usage:  python3 scripts/convert-views.py
"""
import re
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
LEGACY = ROOT / "legacy" / "app" / "Views"
OUT = ROOT / "web" / "views"
OUT.mkdir(parents=True, exist_ok=True)

FILES = {
    "Layout/Starter.php": "layout.hbs",
    "Layout/Header.php": "header.hbs",
    "Auth/login.php": "login.hbs",
    "Auth/register.php": "register.hbs",
    "User/dashboard.php": "dashboard.hbs",
    "User/settings.php": "settings.hbs",
    "Server/Server.php": "server.hbs",
    # views/lib.hbs is hand-written: the original lib.php used a multipart
    # filesystem upload, which cannot work on Vercel's read-only filesystem.
    "Keys/list.php": "keys_list.hbs",
    "Keys/generate.php": "keys_generate.hbs",
    "Keys/key_edit.php": "keys_edit.hbs",
    "Admin/users.php": "admin_users.hbs",
    "Admin/user_edit.php": "admin_user_edit.hbs",
    "Admin/referral.php": "admin_referral.hbs",
}

# Exact tag replacements (applied before the generic rules).
EXACT = {
    "<?= $this->extend('Layout/Starter') ?>": "",
    "<?= $this->section('content') ?>": "",
    "<?= $this->endSection() ?>": "",
    "<?= $this->section('css') ?>": "",
    "<?= $this->section('js') ?>": "",
    "<?= $this->renderSection('content') ?>": "{{{content}}}",
    "<?= $this->renderSection('css') ?>": "{{{pageCss}}}",
    "<?= $this->renderSection('js') ?>": "{{{pageJs}}}",
    "<?= $this->include('Layout/Header') ?>": "{{> header}}",
    "<?= $this->include('Layout/msgStatus') ?>": "{{> msgStatus}}",
    "<?= form_close() ?>": "</form>",
    "<?= form_open() ?>": '<form method="post" action="">',
    "<?= form_open('', ['class' => 'space-y-3 sm:space-y-4']) ?>": '<form method="post" action="" class="space-y-3 sm:space-y-4">',
    "<?= form_open('', ['class' => 'space-y-4 sm:space-y-6']) ?>": '<form method="post" action="" class="space-y-4 sm:space-y-6">',
    "<?= form_open('', ['class' => 'space-y-4']) ?>": '<form method="post" action="" class="space-y-4">',
    "<?= form_open('', ['class' => 'space-y-6']) ?>": '<form method="post" action="" class="space-y-6">',
    "<?= form_open('keys/edit', ['class' => 'space-y-6']) ?>": '<form method="post" action="/keys/edit" class="space-y-6">',
    "<?= link_tag('assets/css/natacode.css') ?>": '<link rel="stylesheet" href="/assets/css/natacode.css">',
    "<?= script_tag('assets/js/natacode.js') ?>": '<script src="/assets/js/natacode.js"></script>',
    '<?= link_tag("https://cdn.datatables.net/1.10.25/css/dataTables.bootstrap5.min.css") ?>': '<link rel="stylesheet" href="https://cdn.datatables.net/1.10.25/css/dataTables.bootstrap5.min.css">',
    '<?= script_tag("https://cdn.datatables.net/1.10.25/js/jquery.dataTables.min.js") ?>': '<script src="https://cdn.datatables.net/1.10.25/js/jquery.dataTables.min.js"></script>',
    '<?= script_tag("https://cdn.datatables.net/1.10.25/js/dataTables.bootstrap5.min.js") ?>': '<script src="https://cdn.datatables.net/1.10.25/js/dataTables.bootstrap5.min.js"></script>',
    "<?= BASE_NAME ?>": "{{brand}}",
    "<?= $title ?>": "{{title}}",
    "<?= esc($title) ?>": "{{title}}",
    "<?= isset($title) ? $title : 'Panel' ?>": "{{title}}",
    "<?= old('max_devices') ?: 1 ?>": "{{form.max_devices}}",
    "<?= date('Y') ?>": "{{year}}",
    "<?= $price ?>": "{{{priceJson}}}",
    "<?= $total_code ?>": "{{totalCodes}}",
    "<?= $key_info->total ?>": "{{key.deviceTotal}}",
    "<?= getName($user) ?>": "{{user.displayName}}",
    "<?= getName($target) ?>": "{{target.displayName}}",
    "<?= getLevel($user->level) ?>": "{{user.levelName}}",
    "<?= $user->level ?>": "{{user.level}}",
    "<?= $user->saldo ?>": "{{user.saldo}}",
    "<?= $user->username ?>": "{{user.username}}",
    "<?= $user->email ?>": "{{user.email}}",
    "<?= $key->id_keys ?>": "{{key.id_keys}}",
    "<?= $key->max_devices ?>": "{{key.max_devices}}",
    "<?= $target->id_users ?>": "{{target.id_users}}",
    "<?= $_SERVER['HTTP_USER_AGENT']; ?>": "{{userAgent}}",
    "<?= $time::now() ?>": "{{now}}",
    "<?= $time::parse($h->created_at)->humanize() ?>": "{{this.createdHuman}}",
    "<?= $time::parse(session()->time_since)->humanize() ?>": "{{sessionSince}}",
    "<?= $time::now()->difference($time::parse(session()->time_login))->humanize() ?>": "{{sessionRemaining}}",
    "<?= HoursToDays($in[2]); ?>": "{{this.durationLabel}}",
    "<?= $in[0] ?>": "{{this.parts.[0]}}",
    "<?= $in[1] ?>": "{{this.parts.[1]}}",
    "<?= $in[3] ?>": "{{this.parts.[3]}}",
    "<?php $in = explode(\"|\", $h->info) ?>": "",
    "<?php $sel_level = ['' => '— Select Roles —', '1' => 'Owner', '2' => 'Admin', '3' => 'Reseller']; ?>": "",
    "<?php $sel_status = ['' => '— Select Status —', '0' => 'Banned/Block', '1' => 'Active',]; ?>": "",
    "<?php $sel_status = ['' => '— Select Status —', '2' => 'Banned/Block', '1' => 'Active', '3' => 'Expired',]; ?>": "",
    "<?php $empty = false; ?>": "",
    "<?php $empty = true; ?>": "",
    "<?php echo \"$getDateTime\"; ?>": "{{stats.expiryFormatted}}",
    "<?php echo $active['devices']; ?>": "{{stats.active}}",
    "<?php echo $inactive['devices']; ?>": "{{stats.inactive}}",
    "<?php echo $keycount['id_keys']; ?>": "{{stats.keys}}",
    "<?php echo $users['id_users']; ?>": "{{stats.users}}",
    "<?php echo $userDetails1['myinput']; ?>": "{{onoff.myinput}}",
    "<?php echo $userDetails1['status']; ?>": "{{onoff.status}}",
    "<?php echo $userDetails2['_ftext']; ?>": "{{ftext._ftext}}",
    "<?php echo $userDetails2['_status']; ?>": "{{ftext._status}}",
    "<?php echo $row['modname']; ?>": "{{server.modname}}",
    "<?php echo $libID['id']; ?>": "{{lib.id}}",
    "<?php echo $libName['file']; ?>": "{{lib.file}}",
    "<?php echo $libPath['file_type']; ?>": "{{lib.file_type}}",
    "<?php echo $libSize['file_size']; ?>": "{{lib.file_size}}",
    "<?php echo $libTime['time']; ?>": "{{lib.time}}",
    "<?php echo $ct ?>": "{{lib.currentTime}}",
    "<?php echo $user_ip ?>": "{{clientIp}}",
    "<?php echo $u->id_users ?>": "{{this.id_users}}",
    "<?php echo site_url() ?>": "/",
    "<?= site_url() ?>": "/",
    "<?= base_url('keys') ?>": "/keys",
    "<?= base_url('user/dashboard') ?>": "/dashboard",
}

# Site URL tags.
for path in ["Server", "admin/create-referral", "admin/manage-users", "keys", "keys/api",
             "keys/deleteExp", "keys/deleteUnused", "keys/generate", "login", "logout",
             "register", "settings"]:
    EXACT[f"<?= site_url('{path}') ?>"] = "/" + path

# Flash data.
for key, hb in [("msgDanger", "flash.danger"), ("msgSuccess", "flash.success"),
                ("msgWarning", "flash.warning"), ("user_key", "flash.user_key"),
                ("game", "flash.game"), ("duration", "flash.duration"),
                ("max_devices", "flash.max_devices")]:
    EXACT[f"<?= session()->getFlashdata('{key}') ?>"] = "{{" + hb + "}}"

# Validation errors.
for field in ["accExpire", "accLevel", "current", "devices", "duration", "email",
              "expiration", "expired_date", "fullname", "game", "max_devices",
              "password", "password2", "referral", "registrator", "saldo",
              "set_saldo", "status", "uplink", "user_key", "username"]:
    EXACT[f"<?= $validation->getError('{field}') ?>"] = "{{errors." + field + "}}"

# old() values.
for field in ["email", "fullname", "referral", "username"]:
    EXACT[f"<?= old('{field}') ?>"] = "{{old." + field + "}}"


def site_url_repl(m):
    return "/" + m.group(1)


GENERIC = [
    # Strip the leading standalone PHP blocks (SQL, includes, helper defs).
    (re.compile(r"\A\s*<\?php.*?\?>\s*", re.S), ""),
    # Remove leftover include() lines inside PHP blocks (e.g. lib view).
    (re.compile(r"<\?php\s*.*?\?>\s*", re.S), lambda m: php_block(m.group(0))),
    # foreach loops
    (re.compile(r"<\?php foreach \(\$(\w+) as \$(\w+)\) :\?>\s*|<\?php foreach \(\$(\w+) as \$(\w+)\) : \?>", re.S), ""),
    (re.compile(r"<\?php (?:endforeach|endforeach;) \?>"), "{{/each}}"),
    # if / elseif / else / endif
    (re.compile(r"<\?php (?:endif;|endif|\}) \?>"), "{{/if}}"),
    (re.compile(r"<\?php (?:else\s*:|else:) \?>"), "{{else}}"),
]


def php_block(block):
    """Convert a control-flow PHP block; drop pure-PHP setup blocks."""
    b = block.strip()
    inner = b[5:-2].strip() if b.startswith("<?php") else b[2:-2].strip()
    m = re.match(r"foreach \(\$(\w+) as \$(\w+)\)", inner)
    if m:
        src, var = m.group(1), m.group(2)
        names = {"code": "codes", "history": "history", "user_list": "userList"}
        return "{{#each " + names.get(src, src) + "}}"
    if inner.startswith("endforeach"):
        return "{{/each}}"
    if inner.startswith("elseif") or inner.startswith("else if"):
        expression = inner.split(")", 1)[0].replace("elseif", "").strip(" (:")
        return "{{else if " + cond(expression) + "}}"
    if inner.startswith("else"):
        return "{{else}}"
    if inner in ("endif;", "endif", "}"):
        return "{{/if}}"
    m = re.match(r"if\s*\((.*?)\)\s*[:{]", inner, re.S)
    if m:
        return "{{#if " + cond(m.group(1)) + "}}"
    if inner.startswith("if "):
        return "{{#if " + cond(inner[3:]) + "}}"
    return ""  # pure setup / SQL / helper declarations are handled server-side


def cond(expr):
    e = expr.strip().strip(":").strip()
    e = e.replace("||", " or ").replace("&&", " and ")
    # Normalise into handlebars subexpressions.
    e = re.sub(r"\(\s*\$user->level\s*==\s*(\d+)\s*\)", r"(eq user.level \1)", e)
    e = re.sub(r"\$user->level\s*==\s*(\d+)", r"(eq user.level \1)", e)
    e = re.sub(r"\$user->level\s*!=\s*(\d+)", r"(neq user.level \1)", e)
    e = re.sub(r"\$u->level\s*==\s*(\d+)", r"(eq this.level \1)", e)
    e = re.sub(r"\$u->status\s*==\s*(\d+)", r"(eq this.status \1)", e)
    e = re.sub(r"\$ModFeatureStatus\['(\w+)'\]\s*==\s*\"on\"", lambda m: '(eq feature.' + m.group(1).lower() + ' "on")', e)
    e = re.sub(r"\$userDetails1\['status'\]\s*==\s*\"on\"", '(eq onoff.status "on")', e)
    e = re.sub(r"\$userDetails2\['_status'\]\s*==\s*\"Safe\"", '(eq ftext._status "Safe")', e)
    e = re.sub(r"\$validation->hasError\('(\w+)'\)", r"errors.\1", e)
    e = re.sub(r"session\(\)->getFlashdata\('(\w+)'\)", r"flash.\1", e)
    e = re.sub(r"session\(\)->has\('userid'\)", "user", e)
    e = re.sub(r"isset\(\$messages\)", "pageMessage", e)
    e = re.sub(r"\$messages\[1\]\s*==\s*'(\w+)'", r'(eq pageMessage.type "\1")', e)
    e = re.sub(r"\$code\b", "codes", e)
    e = re.sub(r"\$keylist\b", "keyList", e)
    e = re.sub(r"\$user_list\b", "userList", e)
    e = re.sub(r"\$empty\b", "emptyRows", e)
    e = e.replace(" or ", " ").replace(" and ", " ")
    # (eq a 1) (eq a 2) -> (or (eq a 1) (eq a 2))
    parts = re.findall(r"\(eq [^)]*\)|\(neq [^)]*\)|\w+(?:\.\w+)*", e)
    if len(parts) > 1 and all(p.startswith("(") for p in parts):
        return "(or " + " ".join(parts) + ")"
    if parts:
        return " ".join(parts)
    return e.strip("() ")


# Loop variable rewrites, applied inside foreach regions.
LOOP_REWRITES = [
    (re.compile(r"<\?= \$c->Referral \?>"), "{{this.referral}}"),
    (re.compile(r"<\?= \$c->(\w+) \?>"), lambda m: "{{this." + m.group(1) + "}}"),
    (re.compile(r"<\?= \$h->(\w+) \?>"), lambda m: "{{this." + m.group(1) + "}}"),
    (re.compile(r"<\?= \$u->(\w+) \?>"), lambda m: "{{this." + m.group(1) + "}}"),
    (re.compile(r"<\?= substr\(\$c->code, 1, 15\) \?>"), "{{this.codeShort}}"),
]


def convert(text, name):
    text = text.replace("\r\n", "\n")
    # Drop the leading PHP preamble (SQL etc.) for views that have one.
    text = re.sub(r"\A<\?php\n.*?\n\?>\n", "", text, flags=re.S)
    # Exact replacements first.
    for k, v in EXACT.items():
        text = text.replace(k, v)
    # Control-flow blocks.
    text = re.sub(r"<\?php.*?\?>", lambda m: php_block(m.group(0)), text, flags=re.S)
    text = re.sub(r"<\?php.*?\?>", "", text, flags=re.S)
    # site_url leftovers
    text = re.sub(r"<\?= site_url\('([^']*)'\) \?>", site_url_repl, text)
    # Loop variable rewrites
    for pattern, repl in LOOP_REWRITES:
        text = re.sub(pattern, repl, text)
    # form_dropdown -> prebuilt select markup injected by the server.
    text = re.sub(r"<\?= form_dropdown\(\[.*?'name' => '(\w+)'.*?\],.*?\) \?>",
                  lambda m: "{{{selects." + m.group(1) + "}}}", text, flags=re.S)
    # old() ?: fallbacks -> server-computed form values
    text = re.sub(r"<\?= old\('(\w+)'\) \?: \(\$\w+->\w+ \?: ''\) \?>", r"{{form.\1}}", text)
    text = re.sub(r"<\?= old\('(\w+)'\) \?: \$(\w+)->(\w+) \?>", r"{{form.\1}}", text)
    text = re.sub(r"<\?= old\('(\w+)'\) \?>", r"{{form.\1}}", text)
    # Conditional expressions inside attributes / labels
    text = re.sub(r"<\?= !\$key->expired_date \? '\([^']*\)' : '' \?>", "{{key.expiredHint}}", text)
    text = re.sub(r"<\?= \(\$key_info->total > \$key->max_devices\) \? 3 : \$key_info->total \?>", "{{key.deviceRows}}", text)
    text = re.sub(r"<\?= old\('devices'\) \?: \(\$key_info->total \? \$key_info->devices : ''\) \?>", "{{form.devices}}", text)
    # Generic echo of $obj->prop
    text = re.sub(r"<\?=\s*\$(\w+)->(\w+)\s*\?>", lambda m: "{{" + OBJ_MAP.get(m.group(1), m.group(1)) + "." + m.group(2) + "}}", text)
    text = re.sub(r"<\?=\s*\$(\w+)\['(\w+)'\]\s*\?>", lambda m: "{{" + ARR_MAP.get(m.group(1), m.group(1)) + "." + m.group(2) + "}}", text)
    # Replace the Tailwind Play CDN with a compiled, local stylesheet (build time).
    text = text.replace('<script src="https://cdn.tailwindcss.com"></script>', "")
    text = text.replace('<!-- Tailwind CSS CDN -->', '<!-- Tailwind CSS (compiled at build time) -->')
    if name in ("layout.hbs", "login.hbs", "register.hbs"):
        text = re.sub(r"(<title>[^<]*</title>)",
                      r'\1\n    <link rel="stylesheet" href="/assets/css/tailwind.css">',
                      text, count=1)
    if name == "keys_generate.hbs":
        # Bulk generation produces several keys; a textarea shows all of them.
        text = text.replace(
            '<input type="text" id="mytext" value="{{flash.user_key}}" class="flex-1 bg-transparent font-mono text-lg font-bold text-purple-600" readonly>',
            '<textarea id="mytext" rows="3" class="flex-1 bg-transparent font-mono text-sm font-bold text-purple-600 resize-none" readonly>{{flash.user_key}}</textarea>')
    if name == "keys_list.hbs":
        # /keys/resetAll deletes a key, so accept `deleted` in the SweetAlert JS.
        text = text.replace("if (data.registered) {\n                                if (data.reset) {",
                            "if (data.registered) {\n                                if (data.deleted || data.reset) {")
    return text


OBJ_MAP = {
    "user": "user", "key": "key", "key_info": "keyInfo", "target": "target",
    "u": "this", "c": "this", "h": "this",
}
ARR_MAP = {
    "userDetails1": "onoff", "userDetails2": "ftext", "credit": "credit",
    "row": "server", "libPath": "lib", "libName": "lib", "libSize": "lib",
    "libTime": "lib", "libID": "lib",
}


def main():
    for src, dst in FILES.items():
        p = LEGACY / src
        if not p.exists():
            print("MISSING", src)
            continue
        out = convert(p.read_text(encoding="utf-8", errors="replace"), dst)
        (OUT / dst).write_text(out, encoding="utf-8")
        leftover = re.findall(r"<\?.*?\?>", out, flags=re.S)
        print(f"{dst:22s} {len(out):6d} chars, {len(leftover):3d} leftover php tags")
        for tag in sorted(set(leftover))[:8]:
            print("    ", tag[:110])


if __name__ == "__main__":
    main()
