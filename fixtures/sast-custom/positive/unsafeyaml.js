const yaml = require('js-yaml');
const express = require('express');
const app = express();
app.post('/parse', (req, res) => {
  const doc = yaml.load(req.body.data);
  res.json(doc);
});
