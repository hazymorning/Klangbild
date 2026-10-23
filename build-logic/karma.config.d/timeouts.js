// Some tests take a minute as WebAssembly, and a test that runs keeps the browser quiet meanwhile.
config.set({
    client: { mocha: { timeout: 0 } },
    browserNoActivityTimeout: 600000,
    pingTimeout: 600000,
});
