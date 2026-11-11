const express = require('express');
const app = express();
app.get('/r', (req, res) => {
  db.reviews.find({ $where: "this.product == " + req.query.id }).then(r => res.json(r));
});
