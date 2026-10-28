import { Component, input } from '@angular/core';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { AdministratorTimeWindow } from '../shared/generated';

export function windowControls(value: AdministratorTimeWindow = { start: '09:00', end: '20:00' }) { return new FormGroup({ start: new FormControl(value.start, { nonNullable: true }), end: new FormControl(value.end, { nonNullable: true }) }); }
export type WindowControls = ReturnType<typeof windowControls>;
export function validWindows(windows: AdministratorTimeWindow[]): boolean {
  return windows.length <= 24 && windows.every(w => /^([01]\d|2[0-3]):[0-5]\d$/.test(w.start) && (/^([01]\d|2[0-3]):[0-5]\d$/.test(w.end) || w.end === '24:00') && w.start < w.end);
}
@Component({
  selector: 'app-time-windows', imports: [ReactiveFormsModule, MatButtonModule],
  template: `<fieldset [disabled]="disabled()"><legend>{{ label() }} start windows</legend>
    @for (window of windows().controls; track window; let index = $index) { <div [formGroup]="window">
      <label [for]="label() + '-start-' + index">Start</label><input [id]="label() + '-start-' + index" formControlName="start" placeholder="09:00" maxlength="5">
      <label [for]="label() + '-end-' + index">End (exclusive)</label><input [id]="label() + '-end-' + index" formControlName="end" placeholder="20:00" maxlength="5">
      <button mat-stroked-button type="button" (click)="windows().removeAt(index)" [disabled]="disabled()">Remove window</button>
    </div> }
    @if (!windows().length) { <p>No start windows.</p> }
    <button mat-stroked-button type="button" (click)="add()" [disabled]="disabled() || windows().length >= 24">Add start window</button>
  </fieldset>`,
  styles: [`fieldset { margin: .5rem 0; } div { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; margin: .5rem 0; } input { width: 6rem; font: inherit; min-height: 2.5rem; }`],
})
export class TimeWindowsComponent {
  readonly windows = input.required<FormArray<WindowControls>>();
  readonly label = input.required<string>();
  readonly disabled = input(false);
  add(): void { if (!this.disabled() && this.windows().length < 24) this.windows().push(windowControls()); }
}
