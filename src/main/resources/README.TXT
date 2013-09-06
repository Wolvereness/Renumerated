
Renumerated - Fixes enum names to match the field identifiers.

  Inspired by OverMapped's inability to fully rename enum constants.

A list of parameters follows in the format of 'pom parameter' - 'property'. A
property is used as a command parameter, like `-Drenum.input=./MyJar.jar',
while the parameter name is used in the pom's plugin-configuration.

  input - renum.input
    This is the jar (or zip) file to read from.

    Required

  output - renum.output
    This is the jar file to write to.

    Default - the input file

  original - renum.original
    The file to copy the original input to, intended for usage when
    overwriting the input file.

    Default - does not copy the original

  cores - renum.cores
    The number of cores to use. This must be at least 1 (intended to start no
    extra threads). Extra threads are used to process classes and perform some
    file-IO.

    Default - 3
