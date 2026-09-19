'use strict';
// Consoles became Apps in 3.5.0 (decision 53). This re-export keeps the old
// require path working for ONE release; lib/apps.js is the module.
module.exports = require('./apps');
