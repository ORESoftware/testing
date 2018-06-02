

### Huge performance disparity between Linux and MacOS

In this case, MacOS is performing 10x or 100x faster, specifically
for the live-mutex library code.

(The disparity was originally found on Node.js version 10.1, but the same disparity also exists on Node version 9).

### to run tests:

1. git clone https://github.com/ORESoftware/testing.git

2. `npm install`

3. `node live-mutex-speed.js`


In the live-mutex library - there is no OS specific code.
There is no check to see which OS it's running on.
Therefore there is simply no OS specific branching.

That should mean that the live-mutex library itself is running
that much slower on Linux/Ubuntu than MacOS.

Extraordinary performance difference, not sure why.

On MacOS, it takes 500ms. On Ubuntu, it takes 39,480ms, almost 100x worse performance.

The library that's experiencing the massive perf difference is located here:
https://github.com/ORESoftware/live-mutex
