import { Component, signal } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';

@Component({
  selector: 'app-root',
  template: '<main>{{ title() }}</main>'
})
class App {
  protected readonly title = signal('angular-phase-14-v1');
}

bootstrapApplication(App).catch(error => console.error(error));
