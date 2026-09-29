module.exports = config => config.set({
  frameworks: ['jasmine'],
  plugins: ['karma-jasmine', 'karma-chrome-launcher', 'karma-jasmine-html-reporter', 'karma-coverage'],
  reporters: ['progress', 'kjhtml'],
  jasmineHtmlReporter: {suppressAll: true},
  coverageReporter: {dir: 'coverage/dev-console', subdir: '.', reporters: [{type: 'html'}, {type: 'text-summary'}]},
  customLaunchers: {
    ChromeHeadlessTests: {
      base: 'ChromeHeadless',
      // Fast navigation tests exceed Chrome's per-frame history update limit and silently lose URL changes.
      // Keep real browser history while lifting this rate limit only in the unit-test browser.
      flags: ['--disable-ipc-flooding-protection']
    }
  }
});
