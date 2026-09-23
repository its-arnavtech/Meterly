import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { adminGuard } from './role.guard';
import { AuthService } from '../services/auth.service';

describe('adminGuard', () => {
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let router: Router;

  function run(): boolean | UrlTree {
    return TestBed.runInInjectionContext(() => adminGuard({} as never, {} as never)) as boolean | UrlTree;
  }

  beforeEach(() => {
    authServiceSpy = jasmine.createSpyObj<AuthService>('AuthService', ['role']);
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: authServiceSpy },
        { provide: Router, useValue: { createUrlTree: (commands: unknown[]) => new UrlTree() } }
      ]
    });
    router = TestBed.inject(Router);
    spyOn(router, 'createUrlTree').and.callThrough();
  });

  it('allows a TENANT_ADMIN through', () => {
    authServiceSpy.role.and.returnValue('TENANT_ADMIN');

    expect(run()).toBeTrue();
    expect(router.createUrlTree).not.toHaveBeenCalled();
  });

  it('redirects a TENANT_USER to the dashboard', () => {
    authServiceSpy.role.and.returnValue('TENANT_USER');

    const result = run();

    expect(result).toBeInstanceOf(UrlTree);
    expect(router.createUrlTree).toHaveBeenCalledWith(['/dashboard']);
  });

  it('redirects when there is no authenticated role', () => {
    authServiceSpy.role.and.returnValue(null);

    const result = run();

    expect(result).toBeInstanceOf(UrlTree);
  });
});
