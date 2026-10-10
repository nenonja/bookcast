import {mkdtemp,readFile,rm} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {execFileSync} from "node:child_process";
const directory=await mkdtemp(join(tmpdir(),"bookcast-contract-"));
try {const output=join(directory,"schema.d.ts");execFileSync("./node_modules/.bin/openapi-typescript",["../api/openapi.json","-o",output],{stdio:"pipe"});if(!Buffer.from(await readFile(output)).equals(await readFile("src/lib/api-schema.d.ts")))throw Error("Generated API types are stale. Run npm run generate:api.");console.log("OpenAPI client is reproducible and current.");}finally{await rm(directory,{recursive:true,force:true});}
