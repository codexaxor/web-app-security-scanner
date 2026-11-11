// Synthetic SAST taint fixture (M8.15 S4) — no real code/secrets. Existing-pack (JS) taint-rule hits.
const express = require('express');
const { Client } = require('pg');
const app = express();
const db = new Client();

// Direct (intra-procedural) tainted SQL string: fires with and without --taint-intrafile.
app.get('/a', (req, res) => {
  const name = req.query.name;
  db.query("SELECT * FROM members WHERE handle = '" + name + "'");
  res.end();
});

// Same-file source helper; the sink is tainted only through inter-procedural (intra-file) flow.
function fromRequest(req) {
  return req.query.account;
}
app.get('/b', (req, res) => {
  const account = fromRequest(req);
  db.query("DELETE FROM ledger WHERE account_no = '" + account + "'");
  res.end();
});
