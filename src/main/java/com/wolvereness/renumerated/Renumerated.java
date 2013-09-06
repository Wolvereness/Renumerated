/*
 * This file is part of Renumerated.
 *
 * Renumerated is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Renumerated is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with Renumerated.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wolvereness.renumerated;

import static org.objectweb.asm.Opcodes.*;
import static com.google.common.collect.Lists.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.wolvereness.renumerated.lib.MultiProcessor;

@Mojo(name="renum")
public class Renumerated extends AbstractMojo implements UncaughtExceptionHandler {

	@Parameter(required=true, property="renum.input")
	private File input;

	@Parameter(required=false, property="renum.output")
	private File output;

	@Parameter(required=false, property="renum.original")
	private File original;

	@Parameter(defaultValue="3", property="renum.cores")
	private int cores;

	private volatile Pair<Thread, Throwable> uncaught;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		final InputStream license = Renumerated.class.getResourceAsStream("/COPYING.HEADER.TXT");
		if (license != null) {
			try {
				getLog().info(new String(ByteStreams.toByteArray(license), "UTF8"));
			} catch (final IOException ex) {
				getLog().warn("Missing LICENSE data", ex);
			} finally {
				try {
					license.close();
				} catch (final IOException e) {
				}
			}
		} else {
			getLog().warn("Missing LICENSE data");
		}
		try {
			process();
		} catch (final MojoExecutionException ex) {
			throw ex;
		} catch (final MojoFailureException ex) {
			throw ex;
		} catch (final Throwable t) {
			throw new MojoExecutionException(null, t);
		}
	}

	private void process() throws Throwable {
		validateInput();

		final MultiProcessor executor = MultiProcessor.newMultiProcessor(cores - 1, new ThreadFactoryBuilder().setDaemon(true).setNameFormat(Renumerated.class.getName() + "-processor-%d").setUncaughtExceptionHandler(this).build());
		final Future<?> fileCopy = executor.submit(
			new Callable<Object>()
				{
					@Override
					public Object call() throws Exception {
						if (original != null) {
							if (original.exists()) {
								original.delete();
							}
							Files.copy(input, original);
						}
						return null;
					}
				}
			);

		final List<Pair<ZipEntry, Future<byte[]>>> fileEntries = newArrayList();
		final List<Pair<MutableObject<ZipEntry>, Future<byte[]>>> classEntries = newArrayList();
		{
			final ZipFile input = new ZipFile(this.input);
			final Enumeration<? extends ZipEntry> inputEntries = input.entries();
			while (inputEntries.hasMoreElements()) {
				final ZipEntry entry = inputEntries.nextElement();
				final Future<byte[]> future = executor.submit(
					new Callable<byte[]>()
						{
							@Override
							public byte[] call() throws Exception {
								return ByteStreams.toByteArray(input.getInputStream(entry));
							}
						}
					);
				if (entry.getName().endsWith(".class")) {
					classEntries.add(new MutablePair<MutableObject<ZipEntry>, Future<byte[]>>(new MutableObject<ZipEntry>(entry), future));
				} else {
					fileEntries.add(new ImmutablePair<ZipEntry, Future<byte[]>>(entry, future));
				}
			}

			for (final Pair<MutableObject<ZipEntry>, Future<byte[]>> pair : classEntries) {
				final byte[] data = pair.getRight().get();
				pair.setValue(executor.submit(
					new Callable<byte[]>()
						{
							String className;
							List<String> fields;

							@Override
							public byte[] call() throws Exception {
								try {
									return method();
								} catch (final Exception ex) {
									throw new Exception(pair.getLeft().getValue().getName(), ex);
								}
							}

							private byte[] method() throws Exception {
								final ClassReader clazz = new ClassReader(data);
								clazz.accept(
									new ClassVisitor(ASM4)
										{
											@Override
											public void visit(final int version, final int access, final String name, final String signature, final String superName, final String[] interfaces) {
												if (superName.equals("java/lang/Enum")) {
													className = name;
												}
											}

											@Override
											public FieldVisitor visitField(final int access, final String name, final String desc, final String signature, final Object value) {
												if (className != null && (access & 0x4000) != 0) {
													List<String> fieldNames = fields;
													if (fieldNames == null) {
														fieldNames = fields = newArrayList();
													}
													fieldNames.add(name);
												}
												return null;
											}
										},
									ClassReader.SKIP_CODE
									);

								if (className == null)
									return data;

								final String classDescriptor = Type.getObjectType(className).getDescriptor();

								final ClassWriter writer = new ClassWriter(0);
								clazz.accept(
									new ClassVisitor(ASM4, writer)
										{
											@Override
											public MethodVisitor visitMethod(final int access, final String name, final String desc, final String signature, final String[] exceptions) {
												final MethodVisitor methodVisitor = super.visitMethod(access, name, desc, signature, exceptions);
												if (!name.equals("<clinit>")) {
													return methodVisitor;
												}
												return new MethodVisitor(ASM4, methodVisitor)
													{
														final Iterator<String> it = fields.iterator();
														boolean active;
														String lastName;

														@Override
														public void visitTypeInsn(final int opcode, final String type) {
															if (!active && it.hasNext()) {
																// Initiate state machine
																if (opcode != NEW)
																	throw new AssertionError("Unprepared for " + opcode + " on " + type + " in " + className);
																active = true;
															}
															super.visitTypeInsn(opcode, type);
														}

														@Override
														public void visitLdcInsn(final Object cst) {
															if (active && lastName == null) {
																if (!(cst instanceof String))
																	throw new AssertionError("Unprepared for " + cst + " in " + className);
																// Switch the first constant in the Enum constructor
																super.visitLdcInsn(lastName = it.next());
															} else {
																super.visitLdcInsn(cst);
															}
														}

														@Override
														public void visitFieldInsn(final int opcode, final String owner, final String name, final String desc) {
															if (opcode == PUTSTATIC && active && lastName != null && owner.equals(className) && desc.equals(classDescriptor) && name.equals(lastName)) {
																// Finish the current state machine
																active = false;
																lastName = null;
															}
															super.visitFieldInsn(opcode, owner, name, desc);
														}
													};
											}
										},
									ClassReader.EXPAND_FRAMES
									);

								final MutableObject<ZipEntry> key = pair.getLeft();
								key.setValue(new ZipEntry(key.getValue().getName()));
								return writer.toByteArray();
							}
						}
					));
			}

			for (final Pair<ZipEntry, Future<byte[]>> pair : fileEntries) {
				pair.getRight().get();
			}

			input.close();
		}

		fileCopy.get();


		FileOutputStream fileOut = null;
		JarOutputStream jar = null;
		try {
			jar = new JarOutputStream(fileOut = new FileOutputStream(output));
			for (final Pair<ZipEntry, Future<byte[]>> fileEntry : fileEntries) {
				jar.putNextEntry(fileEntry.getLeft());
				jar.write(fileEntry.getRight().get());
			}
			for (final Pair<MutableObject<ZipEntry>, Future<byte[]>> classEntry : classEntries) {
				final byte[] data = classEntry.getRight().get();
				final ZipEntry entry = classEntry.getLeft().getValue();
				entry.setSize(data.length);
				jar.putNextEntry(entry);
				jar.write(data);
			}
		} finally {
			if (jar != null) {
				try {
					jar.close();
				} catch (final IOException ex) {
				}
			}
			if (fileOut != null) {
				try {
					fileOut.close();
				} catch (final IOException ex) {
				}
			}
		}

		final Pair<Thread, Throwable> uncaught = this.uncaught;
		if (uncaught != null)
			throw new MojoExecutionException(
				String.format(
					"Uncaught exception in %s",
					uncaught.getLeft()
					),
				uncaught.getRight()
				);
	}

	private void validateInput() throws MojoExecutionException, MojoFailureException {
		if (cores <=0)
			throw new MojoExecutionException(String.format(
				"Cannot process with no cores: `%d'",
				cores
				));
		if (!input.exists() || input.isDirectory())
			throw new MojoFailureException(String.format(
				"Cannot process non-existent input file `%s'",
				input
				));

		verifyOut(output);
		verifyOut(original);
		if (output == null) {
			output = input;
		}
	}

	private void verifyOut(
	                       final File out
	                       ) throws
	                       MojoFailureException
	                       {
		if (out != null){
			if (out.isDirectory())
				throw new MojoFailureException(String.format(
					"Cannot write to directory `%s'",
					out
					));
			final File parent = out.getParentFile();
			if (!parent.isDirectory() && !parent.mkdirs())
				throw new MojoFailureException(String.format(
					"Cannot make directory for `%s'",
					out
					));
		}
	}

	@Override
	public void uncaughtException(final Thread t, final Throwable e) {
		uncaught = new ImmutablePair<Thread, Throwable>(t, e);
	}
}
