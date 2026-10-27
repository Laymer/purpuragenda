import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';
import { captureVerificationProof, VERIFICATION_PROOF } from './app/booking/verification-proof';

const proof = captureVerificationProof(location.pathname, location.hash, () => history.replaceState(null, '', location.pathname));
bootstrapApplication(AppComponent, { ...appConfig, providers: [...appConfig.providers, { provide: VERIFICATION_PROOF, useValue: proof }] }).catch(() => {
  document.body.textContent = 'The application could not start. Please reload the page.';
});
