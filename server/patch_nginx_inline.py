#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把当前站点的 server 块按现有 include 风格内联进 nginx 主配置。

只做「在最后一个 include 之前插入一段」，不重排、不改动其他任何行。
域名、证书、日志、后端地址全部来自项目根的 .env（模板见 .env.example），
脚本里不写死任何真实服务器信息。
"""
import io
import os
import re
import shutil
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_env():
    cfg = {}
    for name in ('.env.example', '.env'):        # .env 覆盖模板占位值
        path = os.path.join(ROOT, name)
        if not os.path.exists(path):
            continue
        with io.open(path, 'r', encoding='utf-8') as f:
            for line in f:
                s = line.strip()
                if not s or s.startswith('#') or '=' not in s:
                    continue
                k, v = s.split('=', 1)
                cfg[k.strip()] = v.strip().strip('"').strip("'")
    return cfg


CFG = load_env()


def render(text):
    def sub(m):
        key = m.group(1)
        if key not in CFG:
            raise SystemExit('配置缺少 %s，请检查 .env / .env.example' % key)
        return CFG[key]
    return re.sub(r'\{\{([A-Z0-9_]+)\}\}', sub, text)


CONF = CFG.get('NGINX_MAIN_CONF', '/etc/nginx/nginx.conf')
ANCHOR = '    ' + CFG.get('NGINX_INCLUDE_ANCHOR', 'include /etc/nginx/conf.d/*.conf;').strip()
DOMAIN = CFG.get('APP_DOMAIN', 'your-domain.example')
MARK = '# ========== StudyTodo sync (%s) ==========' % DOMAIN

BLOCK = render("""    # ========== StudyTodo sync ({{APP_DOMAIN}}) ==========
    # 80 端口：仅放行证书校验文件，其余整站 301 到 HTTPS
    server {
        listen 80;
        server_name {{APP_DOMAIN}};

        location ^~ /.well-known/pki-validation/ {
            default_type text/plain;
            root {{WEBROOT}};
        }

        location / {
            return 301 https://$host$request_uri;
        }
    }

    # 443 端口：页面与同步接口同源
    server {
        listen 443 ssl http2;
        server_name {{APP_DOMAIN}};

        ssl_certificate {{SSL_CERT}};
        ssl_certificate_key {{SSL_KEY}};

        ssl_protocols TLSv1.2 TLSv1.3;
        ssl_ciphers ECDHE-RSA-AES128-GCM-SHA256:ECDHE:ECDH:AES:HIGH:!NULL:!aNULL:!MD5:!ADH:!RC4;
        ssl_prefer_server_ciphers on;

        ssl_session_cache shared:SSL:10m;
        ssl_session_timeout 10m;
        ssl_session_tickets off;

        add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
        add_header X-Frame-Options "SAMEORIGIN" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header X-XSS-Protection "1; mode=block" always;
        add_header X-Site "studytodo" always;

        access_log {{ACCESS_LOG}};
        error_log {{ERROR_LOG}} warn;

        client_max_body_size 20m;

        location / {
            proxy_pass http://{{SYNC_BIND}}:{{SYNC_PORT}};
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;

            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection "upgrade";

            proxy_connect_timeout 30s;
            proxy_send_timeout 120s;
            proxy_read_timeout 120s;
        }

        # 安装包与图标缓存（js/css 不缓存，避免版本更新后拿到旧文件）
        location ~* \\.(apk|jpg|jpeg|png|gif|ico|svg|woff|woff2|ttf|eot)$ {
            proxy_pass http://{{SYNC_BIND}}:{{SYNC_PORT}};
            proxy_set_header Host $host;
            expires 7d;
            add_header Cache-Control "public";
        }
    }

""")


def main():
    if not os.path.exists(CONF):
        print('CONF_NOT_FOUND ' + CONF)
        return 2

    with io.open(CONF, 'r', encoding='utf-8') as f:
        src = f.read()

    if MARK in src:
        print('ALREADY_PATCHED')
        return 0

    if src.count(ANCHOR) != 1:
        print('ANCHOR_NOT_UNIQUE count=%d' % src.count(ANCHOR))
        return 2

    braced_before = src.count('{') - src.count('}')
    out = src.replace(ANCHOR, BLOCK + ANCHOR)
    braced_after = out.count('{') - out.count('}')

    if braced_after != braced_before:
        print('BRACE_MISMATCH before=%d after=%d' % (braced_before, braced_after))
        return 3

    ts = time.strftime('%Y%m%d%H%M%S')
    shutil.copy2(CONF, CONF + '.pre-studytodo-' + ts)

    with io.open(CONF, 'w', encoding='utf-8', newline='\n') as f:
        f.write(out)

    print('PATCHED bytes %d -> %d' % (len(src), len(out)))
    print('BACKUP ' + CONF + '.pre-studytodo-' + ts)
    return 0


if __name__ == '__main__':
    sys.exit(main())
