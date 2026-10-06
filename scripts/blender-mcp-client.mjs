#!/usr/bin/env node

/**
 * Minimal client for Blender's official MCP extension execution bridge.
 *
 * Blender 5.2's extension listens on a null-delimited JSON socket. The public
 * MCP process normally forwards execute_blender_code calls to this bridge; this
 * client keeps PRETYPIST's authoring command reviewable and works even when an
 * already-open Codex task has not refreshed its dynamically exposed tools.
 */

import fs from "node:fs/promises";
import net from "node:net";
import path from "node:path";
import process from "node:process";

const source = process.argv[2];
if (!source) {
  process.stderr.write("Usage: node scripts/blender-mcp-client.mjs PYTHON_FILE\n");
  process.exit(2);
}

const host = process.env.PRETYPIST_BLENDER_HOST || "127.0.0.1";
const port = Number(process.env.PRETYPIST_BLENDER_PORT || 9876);
const sourceCode = await fs.readFile(path.resolve(source), "utf8");
// MCP executes source text rather than a file, so Blender cannot derive the
// repository from __file__. Inject only this invocation's resolved workspace;
// the committed Python remains portable and contains no machine-specific path.
const code = `PRETYPIST_PROJECT = ${JSON.stringify(path.resolve("."))}\n${sourceCode}`;
const request = `${JSON.stringify({type: "execute", code, strict_json: true})}\0`;

const response = await new Promise((resolve, reject) => {
  const socket = net.createConnection({host, port});
  let buffer = Buffer.alloc(0);
  const timeout = setTimeout(() => {
    socket.destroy();
    reject(new Error(`Blender MCP timed out after 120 seconds (${host}:${port})`));
  }, 120_000);

  socket.on("connect", () => socket.write(request));
  socket.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    const delimiter = buffer.indexOf(0);
    if (delimiter === -1) return;
    clearTimeout(timeout);
    socket.end();
    try {
      resolve(JSON.parse(buffer.subarray(0, delimiter).toString("utf8")));
    } catch (error) {
      reject(new Error(`Blender MCP returned invalid JSON: ${error.message}`));
    }
  });
  socket.on("error", (error) => {
    clearTimeout(timeout);
    reject(error);
  });
});

process.stdout.write(`${JSON.stringify(response, null, 2)}\n`);
if (response.status !== "ok") process.exitCode = 1;
