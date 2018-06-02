'use strict';

const async = require('async');
const lmUtils = require('live-mutex/utils');
const {Client} = require('live-mutex/client');
const conf = Object.freeze({port: 7003});
const util = require('util');

process.on('unhandledRejection', function (e) {
  console.error('unhandledRejection => ', e.stack || e);
});

///////////////////////////////////////////////////////////////////

// this simply tests the raw speed of the library (not a sophisticated test)

//////////////////////////////////////////////////////////////////

lmUtils.launchBrokerInChildProcess(conf, function () {

  const client = new Client(conf);

  client.emitter.on('warning', function(){
    console.error(...arguments);
  });

  console.log('this should only be logged once.');

  client.ensure().then(function () {

    const a = Array.apply(null, {length: 1000});
    const start = Date.now();

    var counts = {
      z: 0
    };

    async.eachLimit(a, 50, function (val, cb) {

      client.lock('foo', function (err, unlock) {

        if (err) {
          return cb(err);
        }

        try {
          console.log('unlocking...' + counts.z++);
          unlock(cb);
        }
        catch (err) {
          return cb(err);
        }

      });

    }, function complete(err) {

      if (err) {
        throw err;
      }

      const diff = Date.now() - start;
      console.log(' => Time required for live-mutex => ', diff);
      console.log(' => Lock/unlock cycles per millisecond => ', Number(a.length / diff).toFixed(3));
      process.exit(0);
    });

  });

});




