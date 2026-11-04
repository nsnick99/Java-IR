package com.jir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.*;
import com.jir.engine.SourceEngine;
import com.jir.engine.BytecodeEngine;
import java.nio.file.*;
import java.util.*;

public class Main {
  public static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  public static void main(String[] args) throws Exception {
    Map<String,String> a = parseArgs(args);
    if (a.containsKey("help") || !a.containsKey("input")) { usage(); return; }
    Path input = Path.of(a.get("input")).toAbsolutePath().normalize();
    Path output = Path.of(a.getOrDefault("output", "output/project_ir.json")).toAbsolutePath().normalize();
    String mode = a.getOrDefault("mode", "auto").toLowerCase(Locale.ROOT);
    if (!Files.exists(input)) throw new IllegalArgumentException("Input does not exist: " + input);
    ObjectNode root = JSON.createObjectNode();
    root.put("ir_schema_version", "1.0.0");
    root.put("generator", "java-ir-generator");
    root.put("generator_version", "1.0.0");
    root.put("input", input.toString());
    root.put("generated_at", java.time.Instant.now().toString());
    root.set("project", JSON.createObjectNode().put("name", input.getFileName() == null ? "java-project" : input.getFileName().toString()));
    ArrayNode classes = root.putArray("classes");
    ArrayNode diagnostics = root.putArray("diagnostics");
    boolean source = mode.equals("source") || (mode.equals("auto") && Files.isDirectory(input) && containsJava(input));
    boolean bytecode = mode.equals("bytecode") || (mode.equals("auto") && !source);
    if (source) SourceEngine.analyze(input, classes, diagnostics);
    else if (bytecode) BytecodeEngine.analyze(input, classes, diagnostics);
    else throw new IllegalArgumentException("Mode must be auto, source or bytecode");
    ObjectNode summary = root.putObject("summary");
    summary.put("class_count", classes.size());
    summary.put("diagnostic_count", diagnostics.size());
    Files.createDirectories(output.getParent());
    JSON.writeValue(output.toFile(), root);
    System.out.println("IR written to: " + output);
    System.out.println("Classes analyzed: " + classes.size());
  }
  private static boolean containsJava(Path p) throws Exception { try (var s=Files.walk(p)) { return s.anyMatch(x -> x.toString().endsWith(".java")); } }
  private static Map<String,String> parseArgs(String[] args) {
    Map<String,String> m=new HashMap<>(); for(int i=0;i<args.length;i++){ String x=args[i]; if(x.equals("--help")){m.put("help","true");} else if(x.startsWith("--") && i+1<args.length){m.put(x.substring(2),args[++i]);} else throw new IllegalArgumentException("Unknown/incomplete argument: "+x);} return m;
  }
  private static void usage(){System.out.println("Java IR Generator\nUsage: java -jar java-ir-generator-1.0.0-jar-with-dependencies.jar --input <path> [--mode auto|source|bytecode] [--output <file>]\nSource mode: directory containing .java files. Bytecode mode: .class, .jar or a directory of .class files.");}
}
