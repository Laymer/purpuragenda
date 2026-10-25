import { parseMinor } from './admin.component';
describe('Exact financial input',()=>{
  it('parses decimal amounts using integer arithmetic',()=>{expect(parseMinor('15')).toBe(1500);expect(parseMinor('0.01')).toBe(1);expect(parseMinor('30.5')).toBe(3050);expect(parseMinor('1000000.00')).toBe(100000000);});
  it('rejects fractional cents, exponent notation, negative amounts and over-limit values',()=>{for(const value of ['0.001','1e3','-1','1000000.01','NaN','Infinity','15,00',''])expect(()=>parseMinor(value)).toThrow();});
});
