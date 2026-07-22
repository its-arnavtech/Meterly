import {NgModule} from '@angular/core';
import {RouterModule} from '@angular/router';
import {InvoiceDetailComponent} from './invoice-detail.component';
import {InvoiceListComponent} from './invoice-list.component';

@NgModule({imports: [InvoiceListComponent, InvoiceDetailComponent, RouterModule.forChild([
  {path: '', component: InvoiceListComponent}, {path: ':id', component: InvoiceDetailComponent}
])]})
export class BillingModule {}
