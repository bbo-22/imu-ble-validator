"""Verify moved GATT procedures against the user-accepted v1.2 source baseline."""
import json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
old=subprocess.check_output(['git','-C',str(ROOT),'show','10434a6:src/com/imu/validator/MainActivity.java'],encoding='utf8')
new=(ROOT/'src/com/imu/validator/BleImuManager.java').read_text(encoding='utf8')
def body(source,signature):
 start=source.index(signature);brace=source.index('{',start);end=brace+1;n=1
 while n:n+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[brace:end]
def adapters(s):
 return s.replace('getSystemService(LOCATION_SERVICE)','context.getSystemService(Context.LOCATION_SERVICE)').replace('connectGatt(this,false','connectGatt(context,false').replace('Toast.makeText(this,"请先取消或断开连接",Toast.LENGTH_SHORT).show()','listener.onNotice("请先取消或断开连接")').replace('resetRows();','listener.onScanReset(token);')
result={name:adapters(body(old,name))==body(new,name) for name in ['void startScan()','void stopScanner(','void arm(','void beginGatt(','BluetoothGattCallback callbacks(','void disconnect(','void fail(','void summary()']}
out=dict(baseline='10434a6',moved_procedures=result,allowed_adapters=['application Context receiver','notice callback','scan UI reset callback'],callback_guard='same identity + same attempt + active; truth-table tested')
(ROOT/'reports/ble-extraction-preservation.json').write_text(json.dumps(out,indent=2),encoding='utf8')
print(json.dumps(result,indent=2));assert all(result.values()),'Unexpected GATT behavior edit'
