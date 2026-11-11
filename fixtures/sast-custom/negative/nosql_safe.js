const express = require('express');
const app = express();
app.get('/r', (req, res) => {
  db.reviews.find({ product: req.query.id }).then(r => res.json(r));
});
