#!/usr/bin/env python3
"""
Moves hard-coded UI string literals from Kotlin files into res/values/strings.xml (plan 20).

usage: extract.py <prefix> <file.kt>...   (paths relative to app/src/main/java/com/zenithblue/nativecode)
       extract.py --dry <prefix> <file.kt>...   (print what would change)

A literal is moved only when it is the direct argument of a known UI sink (text = "..", setText(".."),
Toast.makeText(ctx, ".."), title/message/hint/... named args, the project's button/badge builders).
Templates ($x, ${expr}) become positional format strings (%1$s ...). The literal is replaced by
tr(R.string.key, args...). Concatenations, .format() and raw strings are left alone (rewrite by hand).
"""
import os, re, sys, json, xml.sax.saxutils as sx

ROOT = os.path.join(os.path.dirname(__file__), '..', '..', 'app', 'src', 'main')
SRC = os.path.join(ROOT, 'java', 'com', 'zenithblue', 'nativecode')
STRINGS = os.path.join(ROOT, 'res', 'values', 'strings.xml')

NAMED = r'(?:text|hint|contentDescription|tooltipText|title|subtitle|message|confirmLabel|cancelLabel|label|summary|caption|positive|negative|placeholder|detail|hintText|description|status)'
FUNCS = (r'(?:setText|setTitle|setMessage|setHint|setPositiveButton|setNegativeButton|setContentDescription|'
         r'secondaryButton|primaryButton|textBadge|sheetBtn|addSection|buildListLoadingView|chipState|openExternalUrl|'
         r'sectionHeader|actionBtn|outlineBtn|targetBtn|dangerButton|kicker|makeRepairsTabBtn|measureNote|addAction|'
         r'setAction|badge|footer|btn|Text|statusLine|showToast|toast|chip|pill|tabLabel|menuItem|infoRow|row|header)')
SINK_NAMED = re.compile(r'(?<![=!<>])\b' + NAMED + r'\s*=\s*$')
SINK_FUNC = re.compile(r'\b' + FUNCS + r'\(\s*(?:[A-Za-z0-9_.]+\s*,\s*)?$')
SINK_TOAST = re.compile(r'Toast\.makeText\(\s*[^,()]+,\s*$')
SINK_ARG = re.compile(r'\b' + FUNCS + r'\([^()"\n]*,\s*$')


# Context that proves a literal is not user-visible (logs, keys, comparisons, shell, exceptions, files).
EXCL_PRE = re.compile(
    r'(?:\bLog\.\w\(|appendLog\(|Exception\(|\berror\(|\brequire\w*\(|\bcheck\w*\(|println\(|\.put\w*\(|\bput\(|startsWith\(|endsWith\(|'
    r'contains\(|equals\(|[=!]=\s*|\bgetString\(|\bgetBoolean\(|getStringExtra\(|putExtra\(|File\(|Intent\(|Uri\.parse\(|Regex\(|'
    r'\.replace\(|\.split\(|removePrefix\(|removeSuffix\(|\.trim\w*\(|\bsh\b|bash|\bcmd\b|command|script|Pattern|getenv|getProperty|'
    r'@\w+(?:\.\w+)?\(|requestMethod|setRequestProperty|addHeader|setHeader|setType\(|MIME|charset|encoding|\.add\(\s*$|\bTAG\b|\bKEY_?\w*\s*=|const\s+val|\bAction\b|setAction\(|addCategory\(|\bsource\s*=|\bid\s*=|\bname\s*=|\bkey\s*=|\btype\s*=|\bgit\(|\bcmd\w*\()'
)
EXCL_POST = re.compile(r'^\s*(?:->|to\s+\w+\s*[,)]|\.toRegex|\.toInt|\.format\(|\+)')


def generic_ok(line_pre, post, fmt):
    if EXCL_PRE.search(line_pre) or EXCL_POST.match(post): return False
    s = fmt.replace('\x00', '')
    if re.search(r'&&|\|\||2>&1|\$\(|\s-[a-zA-Z]\b|;\s|\bsudo\b|/data/|/usr/|/etc/|\.sh\b|\.json|\.kt\b|\{|\}|<[a-z]|=\w', s): return False
    first = re.search(r'[A-Za-z]', s)
    if not first or not (first.group(0).isupper() or s[:1].isdigit() or s[:1] in '•·'): return False
    letters = sum(c.isalpha() or c == ' ' for c in s)
    return letters >= 0.6 * len(s)


def lex(src):
    """Yield (start, end) of every normal "string" token (not raw, not char, not in comments)."""
    i, n, out = 0, len(src), []
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j; continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2); i = n if j < 0 else j + 2; continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            while j >= 0 and src.startswith('"', j + 3): j += 1
            i = n if j < 0 else j + 3; continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            i = j + 1; continue
        if c == '"':
            j, depth, nested = i + 1, 0, False
            while j < n:
                d = src[j]
                if d == '\\' and depth == 0: j += 2; continue
                if depth == 0 and d == '"': break
                if src.startswith('${', j) and depth == 0: depth = 1; j += 2; continue
                if depth > 0:
                    if d == '{': depth += 1
                    elif d == '}': depth -= 1
                    elif d == '"':  # nested string inside ${...}: skip it (token is then not extractable)
                        nested = True
                        j += 1
                        while j < n and src[j] != '"':
                            j += 2 if src[j] == '\\' else 1
                j += 1
            out.append((i, j + 1, nested)); i = j + 1; continue
        i += 1
    return out


def convert(raw):
    """Kotlin literal body -> (format text, [arg exprs]) or None when not convertible."""
    text, args, i, n = [], [], 0, len(raw)
    while i < n:
        c = raw[i]
        if c == '\\':
            d = raw[i + 1] if i + 1 < n else ''
            if d == 'n': text.append('\n')
            elif d in '"\\$\'': text.append(d)
            elif d == 'u': text.append(chr(int(raw[i + 2:i + 6], 16))); i += 4
            else: return None
            i += 2; continue
        if c == '$':
            if raw.startswith('${', i):
                depth, j = 1, i + 2
                while j < n and depth:
                    depth += (raw[j] == '{') - (raw[j] == '}'); j += 1
                expr = raw[i + 2:j - 1]
                if '"' in expr: return None
                args.append(expr); text.append('\x00'); i = j; continue
            m = re.match(r'\$([A-Za-z_][A-Za-z0-9_]*)', raw[i:])
            if m: args.append(m.group(1)); text.append('\x00'); i += len(m.group(0)); continue
            return None
        text.append(c); i += 1
    return ''.join(text), args


# Technical terms, brands, key caps and tokens that stay English (tools/i18n/GLOSSARY.md).
TECH = set(w.upper() for w in """0GB ABCD-1234 BOOTCLASSPATH DEX2OATBOOTCLASSPATH TMPDIR PREFIX LANG TERM PATH GET POST PUT UTF-8 USD UTC MIT
PULSEAUDIO PANFROST PANVK TURNIP VENUS VIRGL GEMFILE FLUTTER RUBY RUST PHP PYTHON NODE MAVEN GRADLE JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC
ALT CTRL ESC TAB DEL BKSP PGUP PGDN END ENTER SHFT INS CODEX GROK ANTIGRAVITY GOOGLE NATIVECODE PRO SSH APK CPU RAM URL HEAD GIT PROOT ROOT CHROOT
HOST OPENCODE CLAUDE QWEN KIRO HERMES GITHUB LINUX ANDROID SDK NDK JDK ARM64 AUP NATIVE CANON BRAND""".split())


def worth(t):
    s = t.replace('\x00', '')
    if s.strip().upper() in TECH: return False
    if not re.search(r'[A-Za-z\u00c0-\u024f]{2}', s): return False
    if s != s.strip() or '\t' in s: return False
    if '://' in s or s.startswith(('-', '.', '/', '~', '#', '@')) or re.search(r'\b(sh -c|apk |npm |curl |git )', s): return False
    if ' ' in s.strip() or '\n' in s: return True
    return bool(re.match(r'^[A-Z][a-z]{2,}[.…:!?]*$', s) or re.match(r'^[A-Z0-9 &/·….+\-]{3,}$', s))


def xml_text(fmt, nargs):
    t = fmt
    if nargs:
        t = t.replace('%', '%%')
        k = [0]
        def sub(_):
            k[0] += 1; return '%%%d$s' % k[0]
        t = re.sub('\x00', sub, t)
    t = sx.escape(t).replace('\\', '\\\\').replace('"', '\\"').replace("'", "\\'").replace('\n', '\\n')
    t = t.replace('%%', '%%') if nargs else t
    if t.startswith(('@', '?')): t = '\\' + t
    return t


def load_existing():
    keys = {}
    if os.path.exists(STRINGS):
        for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', open(STRINGS).read(), re.S):
            keys[m.group(2)] = m.group(1)
    return keys


def slug(fmt, prefix, used):
    words = re.findall(r'[a-z0-9]+', fmt.replace('\x00', ' ').lower())
    base = prefix + '_' + '_'.join(words[:5])[:36].strip('_') if words else prefix + '_text'
    k, n = base, 2
    while k in used: k = '%s_%d' % (base, n); n += 1
    return k


def process(path, prefix, keys, used, dry):
    full = os.path.join(SRC, path)
    src = open(full).read()
    repl, new_entries = [], []
    toks = lex(src)
    # texts that also appear in a logic position (comparison, key, command) in this file stay in code
    logic = set()
    for (s, e, nested) in toks:
        if nested: continue
        pre = src[max(0, s - 160):s]; lp = pre[pre.rfind('\n') + 1:]
        if EXCL_PRE.search(lp) or EXCL_POST.match(src[e:e + 40]): logic.add(src[s + 1:e - 1])
    for (s, e, nested) in toks:
        if nested: continue
        pre = src[max(0, s - 160):s]
        line_pre = pre[pre.rfind('\n') + 1:] if '\n' in pre else pre
        post = src[e:e + 40]
        if re.match(r'\s*\+', post) or re.search(r'\+\s*$', line_pre) or re.match(r'\s*\.format\(', post): continue
        if src[s + 1:e - 1] in logic: continue
        conv = convert(src[s + 1:e - 1])
        if not conv or not worth(conv[0]): continue
        sink = SINK_NAMED.search(line_pre) or SINK_FUNC.search(line_pre) or SINK_TOAST.search(line_pre) or SINK_ARG.search(line_pre)
        if not sink and not generic_ok(line_pre, post, conv[0]): continue
        fmt, args = conv
        text = xml_text(fmt, len(args))
        key = keys.get(text)
        if not key:
            key = slug(fmt, prefix, used); keys[text] = key; used.add(key); new_entries.append((key, text))
        call = 'tr(R.string.%s%s)' % (key, ''.join(', ' + a for a in args))
        repl.append((s, e, call))
    if not repl: return 0, 0
    if dry:
        for s, e, c in repl[:6]: print('   ', src[s:e][:70], '->', c[:70])
        return len(repl), len(new_entries)
    out = src
    for s, e, c in reversed(repl): out = out[:s] + c + out[e:]
    pkg = re.search(r'^package ([\w.]+)', out, re.M).group(1)
    need = []
    if pkg != 'com.zenithblue.nativecode':
        if 'import com.zenithblue.nativecode.tr\n' not in out: need.append('import com.zenithblue.nativecode.tr')
        if 'import com.zenithblue.nativecode.R\n' not in out: need.append('import com.zenithblue.nativecode.R')
    if need:
        imports = list(re.finditer(r'^import .*$', out, re.M))
        pos = imports[-1].end() if imports else re.search(r'^package .*$', out, re.M).end()
        out = out[:pos] + ('\n' if imports else '\n\n') + '\n'.join(need) + out[pos:]
    open(full, 'w').write(out)
    return len(repl), len(new_entries), new_entries


def main():
    args = sys.argv[1:]
    dry = args and args[0] == '--dry'
    if dry: args = args[1:]
    prefix, files = args[0], args[1:]
    keys = load_existing(); used = set(keys.values())
    added, total = [], 0
    for f in files:
        r = process(f, prefix, keys, used, dry)
        if r[0]:
            print('%4d lits %4d new  %s' % (r[0], r[1], f))
            total += r[0]
            if not dry: added += r[2]
    if not dry and added:
        body = open(STRINGS).read()
        block = '\n    <!-- %s -->\n' % prefix + '\n'.join('    <string name="%s">%s</string>' % (k, t) for k, t in added) + '\n'
        body = body.replace('</resources>', block + '</resources>')
        open(STRINGS, 'w').write(body)
    print('total literals', total, 'new strings', len(added))


main()
