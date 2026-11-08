const needle = require('needle');
const express = require('express');
const app = express();
app.get('/fetch', (req, res) => {
  needle.get("https://api.internal.example.com/status", (err, r) => res.send(r.body));
});
