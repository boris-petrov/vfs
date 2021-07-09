package com.sshtools.vfs.sftp;

import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.provider.AbstractRandomAccessContent;
import org.apache.commons.vfs2.util.RandomAccessMode;

class SftpRandomAccessContent extends AbstractRandomAccessContent {
	private final SftpFileObject fileObject;

	protected long filePointer = 0;
	private DataInputStream dis = null;
	private InputStream mis = null;

	SftpRandomAccessContent(final SftpFileObject fileObject, RandomAccessMode mode) {
		super(mode);

		this.fileObject = fileObject;
	}

	public long getFilePointer() throws IOException {
		return filePointer;
	}

	public void seek(long pos) throws IOException {
		if (pos == filePointer) {
			return;
		}

		if (pos < 0) {
			throw new FileSystemException("vfs.provider/random-access-invalid-position.error", new Object[] { new Long(pos) });
		}
		if (dis != null) {
			close();
		}

		filePointer = pos;
	}

	private void createStream() throws IOException {
		if (dis != null) {
			return;
		}

		mis = fileObject.getInputStream(filePointer);
		dis = new DataInputStream(new FilterInputStream(mis) {
			public int read() throws IOException {
				int ret = super.read();
				if (ret > -1) {
					filePointer++;
				}
				return ret;
			}

			public int read(byte b[]) throws IOException {
				int ret = super.read(b);
				if (ret > -1) {
					filePointer += ret;
				}
				return ret;
			}

			public int read(byte b[], int off, int len) throws IOException {
				int ret = super.read(b, off, len);
				if (ret > -1) {
					filePointer += ret;
				}
				return ret;
			}

			public void close() throws IOException {
				SftpRandomAccessContent.this.close();
			}
		});
	}

	public void close() throws IOException {
		if (dis != null) {
			mis.close();

			// this is to avoid recursive close
			DataInputStream oldDis = dis;
			dis = null;
			oldDis.close();
			mis = null;
		}
	}

	public long length() throws IOException {
		return fileObject.getContent().getSize();
	}

	public byte readByte() throws IOException {
		createStream();
		byte data = dis.readByte();
		return data;
	}

	public char readChar() throws IOException {
		createStream();
		char data = dis.readChar();
		return data;
	}

	public double readDouble() throws IOException {
		createStream();
		double data = dis.readDouble();
		return data;
	}

	public float readFloat() throws IOException {
		createStream();
		float data = dis.readFloat();
		return data;
	}

	public int readInt() throws IOException {
		createStream();
		int data = dis.readInt();
		return data;
	}

	public int readUnsignedByte() throws IOException {
		createStream();
		int data = dis.readUnsignedByte();
		return data;
	}

	public int readUnsignedShort() throws IOException {
		createStream();
		int data = dis.readUnsignedShort();
		return data;
	}

	public long readLong() throws IOException {
		createStream();
		long data = dis.readLong();
		return data;
	}

	public short readShort() throws IOException {
		createStream();
		short data = dis.readShort();
		return data;
	}

	public boolean readBoolean() throws IOException {
		createStream();
		boolean data = dis.readBoolean();
		return data;
	}

	public int skipBytes(int n) throws IOException {
		createStream();
		int data = dis.skipBytes(n);
		return data;
	}

	public void readFully(byte b[]) throws IOException {
		createStream();
		dis.readFully(b);
	}

	public void readFully(byte b[], int off, int len) throws IOException {
		createStream();
		dis.readFully(b, off, len);
	}

	public String readUTF() throws IOException {
		createStream();
		String data = dis.readUTF();
		return data;
	}

	public InputStream getInputStream() throws IOException {
		createStream();
		return dis;
	}

	public void setLength(long newLength) throws IOException {
		throw new UnsupportedOperationException();
	}
}
