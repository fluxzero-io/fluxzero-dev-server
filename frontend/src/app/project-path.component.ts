import {Component, input} from '@angular/core';
import {sendCommand} from './dom-handlers';

@Component({selector: 'dev-project-path', standalone: true, template: `
  <span class="project-path-text">{{path()}}</span>
  <button class="icon-button folder-button" [disabled]="!id() || !exists()"
    [attr.aria-label]="'Open folder ' + path()" [title]="exists() ? 'Open folder in file manager' : 'Folder no longer exists'"
    (click)="open($event)"><i class="bi bi-box-arrow-up-right" aria-hidden="true"></i></button>`})
export class ProjectPathComponent {
  path = input('');
  id = input('');
  exists = input(false);
  open(event: MouseEvent) { sendCommand(event.currentTarget as Element, 'openProjectFolder', this.id()); }
}
