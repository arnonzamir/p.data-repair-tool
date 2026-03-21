#!/usr/bin/env python3
"""
Snowflake SQL proxy for Docker.
Runs on the host machine, accepts SQL queries over HTTP, forwards them to Snowflake.
The Docker container connects to this proxy instead of Snowflake directly.

Usage: python3 snowflake-proxy.py [--port 8099] [--user your-name@sunbit.com]

The proxy authenticates to Snowflake via SSO (opens browser on first query).
"""

import argparse
import json
import os
import sys
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler

try:
    import snowflake.connector
except ImportError:
    print("Installing snowflake-connector-python...")
    os.system(f"{sys.executable} -m pip install --quiet snowflake-connector-python")
    import snowflake.connector


class SnowflakeProxy:
    def __init__(self, user, account, warehouse, role):
        self.user = user
        self.account = account
        self.warehouse = warehouse
        self.role = role
        self.conn = None
        self.lock = threading.Lock()

    def get_connection(self):
        with self.lock:
            if self.conn is None or self.conn.is_closed():
                print(f"Connecting to Snowflake as {self.user}...")
                self.conn = snowflake.connector.connect(
                    account=self.account,
                    user=self.user,
                    authenticator='externalbrowser',
                    warehouse=self.warehouse,
                    role=self.role,
                    database='BRONZE',
                    client_store_temporary_credential=True,
                    client_session_keep_alive=True,
                )
                print("Connected.")
            return self.conn

    def execute(self, sql, params=None):
        conn = self.get_connection()
        cur = conn.cursor()
        try:
            if params:
                cur.execute(sql, params)
            else:
                cur.execute(sql)

            if cur.description is None:
                return {"rows": [], "columns": []}

            columns = [desc[0] for desc in cur.description]
            rows = []
            for row in cur:
                row_dict = {}
                for i, col in enumerate(columns):
                    val = row[i]
                    if val is not None and not isinstance(val, (str, int, float, bool)):
                        val = str(val)
                    row_dict[col] = val
                rows.append(row_dict)

            return {"rows": rows, "columns": columns}
        finally:
            cur.close()


proxy = None


class ProxyHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != '/query':
            self.send_error(404)
            return

        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length).decode('utf-8')

        try:
            req = json.loads(body)
            sql = req.get('sql', '')
            params = req.get('params', None)

            if not sql:
                self.send_error(400, 'Missing sql field')
                return

            result = proxy.execute(sql, params)

            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps(result).encode('utf-8'))

        except Exception as e:
            self.send_response(500)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({"error": str(e)}).encode('utf-8'))

    def do_GET(self):
        if self.path == '/health':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok", "user": proxy.user}).encode('utf-8'))
            return
        self.send_error(404)

    def log_message(self, format, *args):
        print(f"[proxy] {args[0]}")


def main():
    global proxy

    parser = argparse.ArgumentParser(description='Snowflake SQL proxy for Docker')
    parser.add_argument('--port', type=int, default=8099, help='Port to listen on')
    parser.add_argument('--user', default=os.environ.get('SNOWFLAKE_USER', ''), help='Snowflake user')
    parser.add_argument('--account', default='WXA20498.us-west-2.privatelink', help='Snowflake account')
    parser.add_argument('--warehouse', default='DATA_ENG_WH', help='Warehouse')
    parser.add_argument('--role', default='DBT_DEV_ROLE', help='Role')
    args = parser.parse_args()

    if not args.user:
        args.user = input('Snowflake username (your-name@sunbit.com): ')

    proxy = SnowflakeProxy(args.user, args.account, args.warehouse, args.role)

    # Pre-connect (triggers SSO browser)
    proxy.get_connection()

    server = HTTPServer(('0.0.0.0', args.port), ProxyHandler)
    print(f"")
    print(f"Snowflake proxy running on port {args.port}")
    print(f"Docker containers can connect via http://host.docker.internal:{args.port}/query")
    print(f"Press Ctrl+C to stop")
    print(f"")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping proxy...")
        server.shutdown()


if __name__ == '__main__':
    main()
