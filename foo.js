"use strict";

const lmx = require("live-mutex");
const Bluebird = require('bluebird');

const c = new lmx.Client({
  port: 6970,
});

process.once('warning', function (e) {
  console.error('process warning:', e && e.message || e);
});

process.once('unhandledRejection', function (e) {
  console.error('unhandledRejection:', e && e.message || e);
  process.exit(1);
});

process.once('uncaughtException', function (e) {
  console.error('uncaughtException:', e && e.message || e);
  process.exit(1);
});

c.emitter.on('warning', function () {
  console.warn('lmx warning:', ...arguments);
});

c.ensure().then(c => {
    return c.lockp('a', {ttl: 800000, keepLocksAfterDeath: true});
  })
  .then(async res => {
    console.log(`mc1 locked, res.acquired`, res.acquired);
    await Bluebird.delay(10000);
    return c.unlockp('a').then(_ => {
      console.log('mc1 unlocked');
      process.exit(0);
    });
  });


