/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { registerLocaleData } from '@angular/common';
import { HTTP_INTERCEPTORS, provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import en from '@angular/common/locales/en';
import { enableProdMode, inject, provideAppInitializer } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter, withHashLocation } from '@angular/router';

import { APP_ICONS } from '@flink-runtime-web/app-icons';
import { AppComponent } from '@flink-runtime-web/app.component';
import { AppInterceptor } from '@flink-runtime-web/app.interceptor';
import { APP_ROUTES } from '@flink-runtime-web/routes';
import { StatusService } from '@flink-runtime-web/services';
import { NzConfig, provideNzConfig } from 'ng-zorro-antd/core/config';
import { en_US, provideNzI18n } from 'ng-zorro-antd/i18n';
import { provideNzIcons } from 'ng-zorro-antd/icon';

import { environment } from './environments/environment';

if (environment.production) {
  enableProdMode();
}

registerLocaleData(en);

const ngZorroConfig: NzConfig = {
  notification: { nzMaxStack: 1 }
};

bootstrapApplication(AppComponent, {
  providers: [
    {
      provide: HTTP_INTERCEPTORS,
      useClass: AppInterceptor,
      multi: true
    },
    provideAppInitializer(() => inject(StatusService).boot()),
    provideNzI18n(en_US),
    provideNzIcons(APP_ICONS),
    provideNzConfig(ngZorroConfig),
    provideHttpClient(withInterceptorsFromDi()),
    provideAnimationsAsync(),
    provideRouter(APP_ROUTES, withHashLocation())
  ]
}).catch(err => console.error(err));
