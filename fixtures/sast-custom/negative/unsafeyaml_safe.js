const yaml = require('js-yaml');
const doc = yaml.load("name: fixed\nvalue: 1");
module.exports = doc;
