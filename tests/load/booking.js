import http from 'k6/http';
import {check,sleep} from 'k6';
import {Trend,Rate,Counter} from 'k6/metrics';
const fixtures=JSON.parse(open(__ENV.LOAD_CREDENTIALS));
const availability=new Trend('availability_measured',true),mutation=new Trend('mutation_measured',true),unexpected=new Rate('unexpected_5xx'),conflicts=new Counter('expected_conflicts');
export const options={vus:20,duration:'7m',gracefulStop:'15s',thresholds:{availability_measured:['p(95)<500'],mutation_measured:['p(95)<1000'],unexpected_5xx:['rate<0.01'],checks:['rate==1']}};
export default function(){
  const fixture=fixtures[__VU-1];const measured=__ITER>=8;
  const response=http.get(__ENV.BASE_URL+'/api/v1/availability?'+fixture.query,{timeout:'10s',tags:{operation:'availability'}});
  check(response,{'availability succeeds':r=>r.status===200});if(measured){availability.add(response.timings.duration);unexpected.add(response.status>=500);}
  const index=[8,14,20].indexOf(__ITER);
  if(index>=0){const headers={'Content-Type':'application/json','Cookie':fixture.cookie,'X-CSRF-TOKEN':fixture.csrf,'Idempotency-Key':'load-'+__VU+'-'+index};const result=http.post(__ENV.BASE_URL+'/api/v1/bookings',JSON.stringify(fixture.requests[index]),{headers,timeout:'10s',tags:{operation:'mutation'}});if(result.status===409)conflicts.add(1);check(result,{'isolated mutation succeeds':r=>r.status===201});mutation.add(result.timings.duration);unexpected.add(result.status>=500);}
  sleep(15);
}
export function handleSummary(data){return {[__ENV.LOAD_SUMMARY]:JSON.stringify(data,null,2)};}
