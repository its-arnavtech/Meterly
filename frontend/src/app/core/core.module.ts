import {NgModule, Optional, SkipSelf} from '@angular/core';

@NgModule()
export class CoreModule {
  constructor(@Optional() @SkipSelf() parent: CoreModule | null) {
    if (parent) throw new Error('CoreModule must only be imported once.');
  }
}
