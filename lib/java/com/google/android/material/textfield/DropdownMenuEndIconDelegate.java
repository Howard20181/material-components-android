/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.material.textfield;

import com.google.android.material.R;

import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED;
import static androidx.core.view.ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO;
import static androidx.core.view.ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES;
import static com.google.android.material.textfield.EditTextUtils.isEditable;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.text.Editable;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.Spinner;
import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.material.animation.AnimationUtils;
import com.google.android.material.textfield.TextInputLayout.BoxBackgroundMode;

/** Default initialization of the exposed dropdown menu {@link TextInputLayout.EndIconMode}. */
class DropdownMenuEndIconDelegate extends EndIconDelegate {

  @ChecksSdkIntAtLeast(api = VERSION_CODES.LOLLIPOP)
  private static final boolean IS_LOLLIPOP = VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP;

  private static final int ANIMATION_FADE_OUT_DURATION = 50;
  private static final int ANIMATION_FADE_IN_DURATION = 67;

  @Nullable
  private AutoCompleteTextView autoCompleteTextView;

  private final OnClickListener onIconClickListener = view -> showHideDropdown();

  private final OnFocusChangeListener onEditTextFocusChangeListener = (view, hasFocus) -> {
    endLayout.setEndIconActivated(hasFocus);
    if (!hasFocus) {
      setEndIconChecked(false);
      dropdownPopupDirty = false;
    }
  };

  private boolean dropdownPopupDirty = false;
  private boolean isEndIconChecked = false;
  private long dropdownPopupActivatedAt = Long.MAX_VALUE;
  @Nullable private AccessibilityManager accessibilityManager;
  private ValueAnimator fadeOutAnim;
  private ValueAnimator fadeInAnim;

  DropdownMenuEndIconDelegate(@NonNull EndCompoundLayout endLayout) {
    super(endLayout);
  }

  @Override
  void setUp() {
    initAnimators();
    accessibilityManager =
        (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    if (VERSION.SDK_INT >= VERSION_CODES.KITKAT) {
      accessibilityManager.addTouchExplorationStateChangeListener(enabled -> {
        if (autoCompleteTextView != null && !isEditable(autoCompleteTextView)) {
          ViewCompat.setImportantForAccessibility(
              endIconView,
              enabled ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
      });
    }
  }

  @SuppressLint("ClickableViewAccessibility") // There's an accessibility delegate that handles
  // interactions with the dropdown menu.
  @Override
  void tearDown() {
    if (autoCompleteTextView != null) {
      // Remove any listeners set on the edit text.
      autoCompleteTextView.setOnTouchListener(null);
      if (IS_LOLLIPOP) {
        autoCompleteTextView.setOnDismissListener(null);
      }
    }
  }

  @Override
  int getIconDrawableResId() {
    // For lollipop+, the arrow icon changes orientation based on dropdown popup, otherwise it
    // always points down.
    return IS_LOLLIPOP ? R.drawable.mtrl_dropdown_arrow : R.drawable.mtrl_ic_arrow_drop_down;
  }

  @Override
  int getIconContentDescriptionResId() {
    return R.string.exposed_dropdown_menu_content_description;
  }

  @Override
  boolean shouldTintIconOnError() {
    return true;
  }

  @Override
  boolean isBoxBackgroundModeSupported(@BoxBackgroundMode int boxBackgroundMode) {
    return boxBackgroundMode != TextInputLayout.BOX_BACKGROUND_NONE;
  }

  @Override
  OnClickListener getOnIconClickListener() {
    return onIconClickListener;
  }

  @Override
  public void onEditTextAttached(@Nullable EditText editText) {
    this.autoCompleteTextView = castAutoCompleteTextViewOrThrow(editText);
    setUpDropdownShowHideBehavior();
    textInputLayout.setEndIconCheckable(true);
    textInputLayout.setErrorIconDrawable(null);
    if (!isEditable(editText) && accessibilityManager.isTouchExplorationEnabled()) {
      ViewCompat.setImportantForAccessibility(endIconView, IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    textInputLayout.setEndIconVisible(true);
  }

  @Override
  public void afterEditTextChanged(Editable s) {
    // Don't show dropdown list if we're in a11y mode and the menu is editable.
    if (accessibilityManager.isTouchExplorationEnabled()
        && isEditable(autoCompleteTextView)
        && !endIconView.hasFocus()) {
      autoCompleteTextView.dismissDropDown();
    }
    autoCompleteTextView.post(() -> {
      boolean isPopupShowing = autoCompleteTextView.isPopupShowing();
      setEndIconChecked(isPopupShowing);
      dropdownPopupDirty = isPopupShowing;
    });
  }

  @Override
  OnFocusChangeListener getOnEditTextFocusChangeListener() {
    return onEditTextFocusChangeListener;
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(
      View host, @NonNull AccessibilityNodeInfoCompat info) {
    // The non-editable exposed dropdown menu behaves like a Spinner.
    if (!isEditable(autoCompleteTextView)) {
      info.setClassName(Spinner.class.getName());
    }
    if (info.isShowingHintText()) {
      // Set hint text to null so TalkBack doesn't announce the label twice when there is no
      // item selected.
      info.setHintText(null);
    }
  }

  @Override
  public void onPopulateAccessibilityEvent(View host, @NonNull AccessibilityEvent event) {
    // If dropdown is non editable, layout click is what triggers showing/hiding the popup
    // list. Otherwise, arrow icon alone is what triggers it.
    if (event.getEventType() == TYPE_VIEW_CLICKED
        && accessibilityManager.isEnabled()
        && !isEditable(autoCompleteTextView)) {
      showHideDropdown();
      updateDropdownPopupDirty();
    }
  }

  private void showHideDropdown() {
    if (autoCompleteTextView == null) {
      return;
    }
    if (isDropdownPopupActive()) {
      dropdownPopupDirty = false;
    }
    if (!dropdownPopupDirty) {
      if (IS_LOLLIPOP) {
        setEndIconChecked(!isEndIconChecked);
      } else {
        isEndIconChecked = !isEndIconChecked;
        endIconView.toggle();
      }
      if (isEndIconChecked) {
        autoCompleteTextView.requestFocus();
        autoCompleteTextView.showDropDown();
      } else {
        autoCompleteTextView.dismissDropDown();
      }
    } else {
      dropdownPopupDirty = false;
    }
  }

  @SuppressLint("ClickableViewAccessibility") // There's an accessibility delegate that handles
  // interactions with the dropdown menu.
  private void setUpDropdownShowHideBehavior() {
    // Set whole layout clickable.
    autoCompleteTextView.setOnTouchListener((view, event) -> {
      if (event.getAction() == MotionEvent.ACTION_UP) {
        if (isDropdownPopupActive()) {
          dropdownPopupDirty = false;
        }
        showHideDropdown();
        updateDropdownPopupDirty();
      }
      return false;
    });
    if (IS_LOLLIPOP) {
      autoCompleteTextView.setOnDismissListener(() -> {
        updateDropdownPopupDirty();
        setEndIconChecked(false);
      });
    }
    autoCompleteTextView.setThreshold(0);
  }

  private boolean isDropdownPopupActive() {
    long activeFor = System.currentTimeMillis() - dropdownPopupActivatedAt;
    return activeFor < 0 || activeFor > 300;
  }

  @NonNull
  private static AutoCompleteTextView castAutoCompleteTextViewOrThrow(EditText editText) {
    if (!(editText instanceof AutoCompleteTextView)) {
      throw new RuntimeException(
          "EditText needs to be an AutoCompleteTextView if an Exposed Dropdown Menu is being"
              + " used.");
    }

    return (AutoCompleteTextView) editText;
  }

  private void updateDropdownPopupDirty() {
    dropdownPopupDirty = true;
    dropdownPopupActivatedAt = System.currentTimeMillis();
  }

  private void setEndIconChecked(boolean checked) {
    if (isEndIconChecked != checked) {
      isEndIconChecked = checked;
      fadeInAnim.cancel();
      fadeOutAnim.start();
    }
  }

  private void initAnimators() {
    fadeInAnim = getAlphaAnimator(ANIMATION_FADE_IN_DURATION, 0, 1);
    fadeOutAnim = getAlphaAnimator(ANIMATION_FADE_OUT_DURATION, 1, 0);
    fadeOutAnim.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator animation) {
            endIconView.setChecked(isEndIconChecked);
            fadeInAnim.start();
          }
        });
  }

  private ValueAnimator getAlphaAnimator(int duration, float... values) {
    ValueAnimator animator = ValueAnimator.ofFloat(values);
    animator.setInterpolator(AnimationUtils.LINEAR_INTERPOLATOR);
    animator.setDuration(duration);
    animator.addUpdateListener(animation -> {
      float alpha = (float) animation.getAnimatedValue();
      endIconView.setAlpha(alpha);
    });

    return animator;
  }
}
