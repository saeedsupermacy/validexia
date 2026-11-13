package com.validexia.documents.application;

/**
 * Raised when an uploaded file fails the document slice's validation rules (type, size, name).
 * The message is safe to return to the caller: it never contains file system paths or file
 * contents.
 */
public final class DocumentUploadException extends RuntimeException {

  public DocumentUploadException(String message) {
    super(message);
  }
}
