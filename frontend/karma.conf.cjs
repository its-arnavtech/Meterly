module.exports = function configureKarma(config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
      require('@angular-devkit/build-angular/plugins/karma')
    ],
    client: {
      clearContext: false
    },
    reporters: ['progress', 'kjhtml'],
    restartOnFileChange: true,
    customLaunchers: {
      ChromeHeadlessMeterly: {
        base: 'ChromeHeadless',
        flags: [
          '--disable-gpu',
          '--disable-dev-shm-usage',
          '--no-sandbox',
          '--no-first-run',
          '--disable-background-networking'
        ]
      }
    }
  });
};
