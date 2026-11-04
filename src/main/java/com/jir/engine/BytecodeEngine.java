package com.jir.engine;

import com.fasterxml.jackson.databind.node.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.Stream;

public final class BytecodeEngine {
 private BytecodeEngine() {}
 public static void analyze(Path input,ArrayNode classes,ArrayNode diagnostics)throws Exception{
  if(Files.isDirectory(input)){try(Stream<Path>s=Files.walk(input)){for(Path p:s.filter(x->x.toString().endsWith(".class")).toList())readClass(Files.readAllBytes(p),p.toString(),classes,diagnostics);}}
  else if(input.toString().endsWith(".jar")){try(JarFile jar=new JarFile(input.toFile())){Enumeration<JarEntry> e=jar.entries();while(e.hasMoreElements()){JarEntry x=e.nextElement();if(!x.isDirectory()&&x.getName().endsWith(".class")){try(InputStream in=jar.getInputStream(x)){readClass(in.readAllBytes(),input+"!"+x.getName(),classes,diagnostics);}}}}}
  else if(input.toString().endsWith(".class"))readClass(Files.readAllBytes(input),input.toString(),classes,diagnostics);
  else throw new IllegalArgumentException("Bytecode input must be .class, .jar or a directory of .class files");
 }
 private static void readClass(byte[] bytes,String origin,ArrayNode classes,ArrayNode diagnostics){
  try{ClassNode cn=new ClassNode();new ClassReader(bytes).accept(cn,ClassReader.EXPAND_FRAMES);ObjectNode c=classes.addObject();c.put("name",cn.name.replace('/','.'));c.put("qualified_name",cn.name.replace('/','.'));c.put("kind",(cn.access&Opcodes.ACC_ANNOTATION)!=0?"ANNOTATION":(cn.access&Opcodes.ACC_ENUM)!=0?"ENUM":(cn.access&Opcodes.ACC_INTERFACE)!=0?"INTERFACE":"CLASS");c.put("origin","BYTECODE");c.put("class_file",origin);c.put("bytecode_version",cn.version);c.put("access",cn.access);c.put("super_name",cn.superName==null?null:cn.superName.replace('/','.'));
   ArrayNode interfaces=c.putArray("interfaces");if(cn.interfaces!=null)cn.interfaces.forEach(x->interfaces.add(x.replace('/','.')));
   ArrayNode fields=c.putArray("fields");for(FieldNode f:cn.fields){ObjectNode x=fields.addObject();x.put("name",f.name);x.put("descriptor",f.desc);x.put("signature",f.signature);x.put("access",f.access);x.put("constant_value",f.value==null?null:String.valueOf(f.value));}
   ArrayNode methods=c.putArray("methods");for(MethodNode m:cn.methods)methods.add(methodIR(cn.name,m,diagnostics,origin));
  }catch(Exception ex){ObjectNode d=diagnostics.addObject();d.put("code","BYTECODE_ERROR");d.put("file",origin);d.put("message",ex.toString());}
 }
 private static ObjectNode methodIR(String owner,MethodNode m,ArrayNode diagnostics,String origin){
  ObjectNode n=JsonNodeFactory.instance.objectNode();n.put("name",m.name);n.put("descriptor",m.desc);n.put("signature",m.signature);n.put("access",m.access);n.put("max_stack",m.maxStack);n.put("max_locals",m.maxLocals);
  Type mt=Type.getMethodType(m.desc);n.put("return_type",mt.getReturnType().getClassName());ArrayNode params=n.putArray("parameters");for(Type t:mt.getArgumentTypes())params.add(t.getClassName());
  ArrayNode ins=n.putArray("instructions"), calls=n.putArray("calls"), cfg=n.putArray("cfg_nodes"), edges=n.putArray("cfg_edges"), handlers=n.putArray("exception_handlers");
  IdentityHashMap<AbstractInsnNode,Integer> ids=new IdentityHashMap<>();int idx=0;for(AbstractInsnNode x=m.instructions.getFirst();x!=null;x=x.getNext())ids.put(x,idx++);
  for(AbstractInsnNode x=m.instructions.getFirst();x!=null;x=x.getNext()){int id=ids.get(x);ObjectNode q=ins.addObject();q.put("id",id);q.put("opcode",x.getOpcode());q.put("opcode_name",opcodeName(x.getOpcode()));q.put("node_type",x.getClass().getSimpleName());if(x instanceof VarInsnNode v)q.put("var",v.var);if(x instanceof IntInsnNode v)q.put("operand",v.operand);if(x instanceof LdcInsnNode v)q.put("constant",String.valueOf(v.cst));if(x instanceof FieldInsnNode v){q.put("owner",v.owner);q.put("name",v.name);q.put("descriptor",v.desc);}if(x instanceof MethodInsnNode v){q.put("owner",v.owner);q.put("name",v.name);q.put("descriptor",v.desc);ObjectNode call=calls.addObject();call.put("owner",v.owner.replace('/','.'));call.put("name",v.name);call.put("descriptor",v.desc);call.put("instruction",id);}if(x instanceof TypeInsnNode v)q.put("type",v.desc);if(x instanceof JumpInsnNode v)q.put("target",ids.get(v.label));if(x instanceof IincInsnNode v){q.put("var",v.var);q.put("increment",v.incr);}if(x instanceof TableSwitchInsnNode v){q.put("min",v.min);q.put("max",v.max);q.put("default",ids.get(v.dflt));}if(x instanceof LookupSwitchInsnNode v){q.put("default",ids.get(v.dflt));q.put("keys",v.keys.toString());}
   ObjectNode b=cfg.addObject();b.put("id",id);b.put("instruction",id);b.put("opcode",opcodeName(x.getOpcode()));
  }
  for(AbstractInsnNode x=m.instructions.getFirst();x!=null;x=x.getNext()){int id=ids.get(x);int op=x.getOpcode();if(x instanceof JumpInsnNode j){Integer target=ids.get(j.label);if(target!=null)edge(edges,id,target,"BRANCH");if(op!=Opcodes.GOTO&&op!=Opcodes.JSR&&x.getNext()!=null)edge(edges,id,ids.get(x.getNext()),"FALLTHROUGH");}else if(x instanceof TableSwitchInsnNode s){edge(edges,id,ids.get(s.dflt),"SWITCH_DEFAULT");for(LabelNode l:s.labels)edge(edges,id,ids.get(l),"SWITCH_CASE");}else if(x instanceof LookupSwitchInsnNode s){edge(edges,id,ids.get(s.dflt),"SWITCH_DEFAULT");for(LabelNode l:s.labels)edge(edges,id,ids.get(l),"SWITCH_CASE");}else if(!terminal(op)&&x.getNext()!=null)edge(edges,id,ids.get(x.getNext()),"FALLTHROUGH");}
  for(TryCatchBlockNode h:m.tryCatchBlocks){ObjectNode x=handlers.addObject();x.put("start",ids.get(h.start));x.put("end",ids.get(h.end));x.put("handler",ids.get(h.handler));x.put("type",h.type==null?"java.lang.Throwable":h.type.replace('/','.'));}
  try{Analyzer<SourceValue>a=new Analyzer<>(new SourceInterpreter());Frame<SourceValue>[] frames=a.analyze(owner,m);ArrayNode flow=n.putArray("data_flow_frames");for(int i=0;i<frames.length;i++){Frame<SourceValue> f=frames[i];if(f==null)continue;ObjectNode z=flow.addObject();z.put("instruction",i);z.put("stack_size",f.getStackSize());z.put("locals",f.getLocals());ArrayNode stack=z.putArray("stack");for(int k=0;k<f.getStackSize();k++){SourceValue v=f.getStack(k);ObjectNode sv=stack.addObject();sv.put("size",v.getSize());ArrayNode src=sv.putArray("source_instructions");for(AbstractInsnNode producer:v.insns){Integer pi=ids.get(producer);if(pi!=null)src.add(pi);}}}}
  catch(AnalyzerException ex){ObjectNode d=diagnostics.addObject();d.put("code","DATA_FLOW_ANALYSIS_WARNING");d.put("file",origin);d.put("method",owner+"."+m.name+m.desc);d.put("message",ex.getMessage());}
  n.set("instructions",ins);n.set("calls",calls);n.set("cfg_nodes",cfg);n.set("cfg_edges",edges);n.set("exception_handlers",handlers);n.put("data_flow_precision","ASM_SOURCE_INTERPRETER_PRODUCER_FRAMES");return n;
 }
 private static boolean terminal(int op){return op>=Opcodes.IRETURN&&op<=Opcodes.RETURN||op==Opcodes.ATHROW||op==Opcodes.RET;}
 private static void edge(ArrayNode a,int f,int t,String k){ObjectNode e=a.addObject();e.put("from",f);e.put("to",t);e.put("kind",k);}
 private static String opcodeName(int op){if(op<0)return "LABEL_OR_PSEUDO";try{java.lang.reflect.Field[] fs=Opcodes.class.getFields();for(var f:fs)if(f.getType()==int.class&&f.getInt(null)==op&&f.getName().matches("[A-Z][A-Z0-9_]*"))return f.getName();}catch(Exception ignored){}return "OPCODE_"+op;}
}
