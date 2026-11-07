const express = require('express');
const app = express();
const db = require('./db');

app.get('/u', (req, res) => {
  db.query("SELECT * FROM users WHERE id = '" + req.query.id + "'");
  res.send("<div>" + req.query.name + "</div>");
  eval(req.query.code);
});
