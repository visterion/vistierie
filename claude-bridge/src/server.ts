import express from "express";
import { complete } from "./complete.js";
import { BridgeError, type CompleteRequest } from "./types.js";

const app = express();
app.use(express.json({ limit: "50mb" })); // base64 images

app.get("/healthz", (_req, res) => {
  res.json({ status: "ok" });
});

app.post("/v1/complete", async (req, res) => {
  const body = req.body as Partial<CompleteRequest>;
  if (!body?.model || !Array.isArray(body.messages) || body.messages.length === 0) {
    res.status(400).json({ error: { code: "invalid_request", message: "model and messages are required" } });
    return;
  }
  // Abort the in-flight SDK query if the client disconnects before we respond,
  // so a client/Java-side timeout also tears down the spawned CLI child process.
  const ac = new AbortController();
  res.on("close", () => {
    if (!res.writableEnded) ac.abort();
  });
  try {
    res.json(await complete(body as CompleteRequest, { signal: ac.signal }));
  } catch (err) {
    const e = err instanceof BridgeError ? err : new BridgeError(500, "sdk_error", String(err));
    console.error(`complete failed: ${e.status} ${e.code} ${e.message}`);
    res.status(e.status).json({ error: { code: e.code, message: e.message } });
  }
});

const port = Number(process.env.PORT ?? 8091);
app.listen(port, () => console.log(`claude-bridge listening on :${port}`));
