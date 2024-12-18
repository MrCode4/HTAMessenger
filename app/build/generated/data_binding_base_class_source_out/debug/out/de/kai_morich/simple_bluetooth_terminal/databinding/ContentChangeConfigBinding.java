// Generated by view binder compiler. Do not edit!
package de.kai_morich.simple_bluetooth_terminal.databinding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.viewbinding.ViewBinding;
import de.kai_morich.simple_bluetooth_terminal.R;
import java.lang.NullPointerException;
import java.lang.Override;

public final class ContentChangeConfigBinding implements ViewBinding {
  @NonNull
  private final ConstraintLayout rootView;

  private ContentChangeConfigBinding(@NonNull ConstraintLayout rootView) {
    this.rootView = rootView;
  }

  @Override
  @NonNull
  public ConstraintLayout getRoot() {
    return rootView;
  }

  @NonNull
  public static ContentChangeConfigBinding inflate(@NonNull LayoutInflater inflater) {
    return inflate(inflater, null, false);
  }

  @NonNull
  public static ContentChangeConfigBinding inflate(@NonNull LayoutInflater inflater,
      @Nullable ViewGroup parent, boolean attachToParent) {
    View root = inflater.inflate(R.layout.content_change_config, parent, false);
    if (attachToParent) {
      parent.addView(root);
    }
    return bind(root);
  }

  @NonNull
  public static ContentChangeConfigBinding bind(@NonNull View rootView) {
    if (rootView == null) {
      throw new NullPointerException("rootView");
    }

    return new ContentChangeConfigBinding((ConstraintLayout) rootView);
  }
}
