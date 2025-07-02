def buildLog = new File(basedir, "build.log")
assert !buildLog.text.contains("Illegal character in query")