package com.jir.engine;

import com.fasterxml.jackson.databind.node.*;
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class SourceEngine {
 private SourceEngine() {}
 public static void analyze(Path root, ArrayNode classes, ArrayNode diagnostics) throws Exception {
  List<Path> files=new ArrayList<>();
  if(Files.isRegularFile(root)&&root.toString().endsWith(".java")) files.add(root);
  else try(Stream<Path> s=Files.walk(root)){s.filter(p->p.toString().endsWith(".java")).forEach(files::add);}
  ParserConfiguration cfg=new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
  JavaParser parser=new JavaParser(cfg);
  for(Path file:files){
   try { ParseResult<CompilationUnit> result=parser.parse(file); if(result.getResult().isEmpty()){diagnostic(diagnostics,"PARSE_ERROR",file.toString(),result.getProblems().toString());continue;} CompilationUnit cu=result.getResult().get();
    String pkg=cu.getPackageDeclaration().map(x->x.getNameAsString()).orElse("");
    for(TypeDeclaration<?> type:cu.getTypes()) if(type instanceof ClassOrInterfaceDeclaration || type instanceof EnumDeclaration || type instanceof RecordDeclaration || type instanceof AnnotationDeclaration) classes.add(classIR(type,pkg,file,cu));
   } catch(Exception e){diagnostic(diagnostics,"SOURCE_ERROR",file.toString(),e.getMessage());}
  }
 }
 private static ObjectNode classIR(TypeDeclaration<?> t,String pkg,Path file,CompilationUnit cu){
  ObjectNode c=JsonNodeFactory.instance.objectNode(); c.put("name",t.getNameAsString()); c.put("qualified_name",pkg.isBlank()?t.getNameAsString():pkg+"."+t.getNameAsString()); c.put("kind",kind(t)); c.put("origin","SOURCE"); c.put("source_file",file.toString()); c.put("start_line",t.getBegin().map(p->p.line).orElse(-1)); c.put("end_line",t.getEnd().map(p->p.line).orElse(-1));
  strings(c.putArray("modifiers"),t.getModifiers().stream().map(Object::toString).toList());
  ArrayNode imports=c.putArray("imports"); cu.getImports().forEach(i->imports.add(i.getNameAsString()+(i.isAsterisk()?".*":"")));
  ArrayNode anns=c.putArray("annotations"); t.getAnnotations().forEach(a->anns.add(a.toString()));
  ArrayNode supers=c.putArray("extends"); if(t instanceof ClassOrInterfaceDeclaration x) x.getExtendedTypes().forEach(v->supers.add(v.toString())); if(t instanceof EnumDeclaration x) x.getImplementedTypes().forEach(v->supers.add(v.toString()));
  ArrayNode impl=c.putArray("implements"); if(t instanceof ClassOrInterfaceDeclaration x) x.getImplementedTypes().forEach(v->impl.add(v.toString())); if(t instanceof RecordDeclaration x) x.getImplementedTypes().forEach(v->impl.add(v.toString()));
  ArrayNode fields=c.putArray("fields"), methods=c.putArray("methods"), constructors=c.putArray("constructors");
  for(BodyDeclaration<?> member:t.getMembers()){
   if(member instanceof FieldDeclaration f) for(VariableDeclarator v:f.getVariables()){ObjectNode n=fields.addObject();n.put("name",v.getNameAsString());n.put("type",v.getType().toString());n.put("visibility",visibility(f));n.put("static",f.isStatic());n.put("final",f.isFinal());n.put("initializer",v.getInitializer().map(Object::toString).orElse(null));n.put("start_line",v.getBegin().map(p->p.line).orElse(-1));}
   else if(member instanceof MethodDeclaration m) methods.add(methodIR(m));
   else if(member instanceof ConstructorDeclaration k) constructors.add(methodIR(k));
  }
  ArrayNode nested=c.putArray("nested_types"); for(BodyDeclaration<?> m:t.getMembers()) if(m instanceof TypeDeclaration<?> nt) nested.add(nt.getNameAsString());
  c.put("source_ir_level","AST_PLUS_STRUCTURAL_CONTROL_FLOW"); return c;
 }
 private static ObjectNode methodIR(CallableDeclaration<?> m){
  ObjectNode n=JsonNodeFactory.instance.objectNode(); n.put("name",m.getNameAsString()); n.put("kind",m instanceof ConstructorDeclaration?"CONSTRUCTOR":"METHOD");
  n.put("return_type",m instanceof MethodDeclaration md?md.getType().toString():"<constructor>"); n.put("visibility",visibility(m)); n.put("static",m.getModifiers().toString().contains("static")); n.put("abstract",m instanceof MethodDeclaration md&&md.isAbstract()); n.put("start_line",m.getBegin().map(p->p.line).orElse(-1));
  ArrayNode params=n.putArray("parameters");m.getParameters().forEach(p->{ObjectNode x=params.addObject();x.put("name",p.getNameAsString());x.put("type",p.getType().toString());x.put("varargs",p.isVarArgs());});
  ArrayNode throwsA=n.putArray("throws");m.getThrownExceptions().forEach(x->throwsA.add(x.toString()));
  ArrayNode annotations=n.putArray("annotations");m.getAnnotations().forEach(x->annotations.add(x.toString()));
  ArrayNode calls=n.putArray("calls"), expressions=n.putArray("expressions"), cfg=n.putArray("cfg_nodes"), edges=n.putArray("cfg_edges"), variables=n.putArray("variable_accesses");
  Optional<BlockStmt> methodBody = m instanceof MethodDeclaration md ? md.getBody() : Optional.of(((ConstructorDeclaration)m).getBody());
  methodBody.ifPresent(body->{
   body.findAll(MethodCallExpr.class).forEach(x->{ObjectNode q=calls.addObject();q.put("name",x.getNameAsString());q.put("scope",x.getScope().map(Object::toString).orElse("this"));q.put("arguments",x.getArguments().toString());q.put("line",x.getBegin().map(p->p.line).orElse(-1));});
   body.findAll(Expression.class).stream().filter(x->x instanceof AssignExpr||x instanceof BinaryExpr||x instanceof UnaryExpr||x instanceof ConditionalExpr||x instanceof ObjectCreationExpr).forEach(x->{ObjectNode q=expressions.addObject();q.put("kind",x.getClass().getSimpleName());q.put("text",x.toString());q.put("line",x.getBegin().map(p->p.line).orElse(-1));});
   body.findAll(NameExpr.class).forEach(x->{ObjectNode q=variables.addObject();q.put("name",x.getNameAsString());q.put("access","READ_OR_WRITE_UNRESOLVED");q.put("line",x.getBegin().map(p->p.line).orElse(-1));});
   buildCfg(body,cfg,edges);
  });
  n.set("calls",calls);n.set("expressions",expressions);n.set("cfg_nodes",cfg);n.set("cfg_edges",edges);n.set("variable_accesses",variables);
  n.put("data_flow_precision","AST_HEURISTIC_NOT_SSA"); n.put("symbol_resolution","BEST_EFFORT_NOT_ENABLED_WITHOUT_PROJECT_CLASSPATH"); return n;
 }
 private static void buildCfg(BlockStmt body,ArrayNode nodes,ArrayNode edges){
  List<Statement> ss=body.getStatements(); int prev=-1; for(int i=0;i<ss.size();i++){Statement s=ss.get(i);ObjectNode n=nodes.addObject();n.put("id",i);n.put("kind",stmtKind(s));n.put("text",s.toString());n.put("line",s.getBegin().map(p->p.line).orElse(-1)); if(prev>=0)edge(edges,prev,i,"FALLTHROUGH"); prev=i;
   if(s instanceof IfStmt){int branch=i+1;edge(edges,i,branch,"TRUE");edge(edges,i,branch,"FALSE_APPROXIMATE");}
   else if(s instanceof WhileStmt||s instanceof ForStmt||s instanceof ForEachStmt||s instanceof DoStmt){edge(edges,i,i,"LOOP_BACK_APPROXIMATE");}
   else if(s instanceof ReturnStmt||s instanceof ThrowStmt){n.put("terminal",true);prev=-1;}
  }
 }
 private static String stmtKind(Statement s){if(s instanceof IfStmt)return "BRANCH";if(s instanceof WhileStmt||s instanceof ForStmt||s instanceof ForEachStmt||s instanceof DoStmt)return "LOOP";if(s instanceof ReturnStmt)return "RETURN";if(s instanceof ThrowStmt)return "THROW";if(s instanceof TryStmt)return "TRY";if(s instanceof SwitchStmt)return "SWITCH";return "STATEMENT";}
 private static void edge(ArrayNode a,int f,int t,String k){ObjectNode e=a.addObject();e.put("from",f);e.put("to",t);e.put("kind",k);}
 private static String kind(TypeDeclaration<?> t){if(t instanceof EnumDeclaration)return "ENUM";if(t instanceof RecordDeclaration)return "RECORD";if(t instanceof AnnotationDeclaration)return "ANNOTATION";if(t instanceof ClassOrInterfaceDeclaration x)return x.isInterface()?"INTERFACE":"CLASS";return "UNKNOWN";}
 private static String visibility(NodeWithModifiers<?> d){String s=d.getModifiers().toString();if(s.contains("public"))return "PUBLIC";if(s.contains("protected"))return "PROTECTED";if(s.contains("private"))return "PRIVATE";return "PACKAGE_PRIVATE";}
 private static void strings(ArrayNode a,List<String> xs){xs.forEach(a::add);}
 private static void diagnostic(ArrayNode a,String code,String file,String message){ObjectNode n=a.addObject();n.put("code",code);n.put("file",file);n.put("message",String.valueOf(message));}
}
