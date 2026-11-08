const needle = require('needle');
const express = require('express');
const app = express();
app.get('/fetch', (req, res) => {
  needle.get(req.query.url, (err, r) => res.send(r.body));
});
