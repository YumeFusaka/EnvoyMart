/** 只读取原报告，独立写重判文件；不覆盖原BLOCKED，不执行SQL/模型/服务操作。 */
import {readFile,writeFile} from 'node:fs/promises'
import {resolve,isAbsolute} from 'node:path'
import {createHash} from 'node:crypto'
import {reassessLegacyPhase1} from './lib/write-receipt-phase1.mjs'
const args=process.argv.slice(2),path=args[0]?.startsWith('--from=')?args[0].slice(7):null
if(args.length!==1||!path||!isAbsolute(path))throw new Error('仅支持--from=<绝对保留原报告路径>')
const raw=await readFile(path,'utf8'),source=JSON.parse(raw),assessment=reassessLegacyPhase1(source)
const output=resolve(path.replace(/\.json$/,'.reassessed.json'))
if(output===resolve(path))throw new Error('禁止覆盖原报告')
await writeFile(output,JSON.stringify({version:'seata-phase1-reassessment-v1',runId:source.runId,sourcePath:path,
  sourceDigest:createHash('sha256').update(raw).digest('hex'),sourceStatus:source.status,assessment,createdAt:new Date().toISOString()},null,2)+'\n',{flag:'wx'})
if(assessment.status!=='PASS')process.exitCode=1
console.log(JSON.stringify({status:assessment.status,path:output,reason:assessment.reason,sourceStatus:source.status}))
