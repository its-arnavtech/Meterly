import { TestBed } from '@angular/core/testing';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({imports: [AppComponent]}).compileComponents();
  });

  it('identifies the Phase 0 workspace', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('METERLY · PHASE 0');
  });
});

