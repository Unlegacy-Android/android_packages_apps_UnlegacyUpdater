package org.unlegacy_android.updater;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewDebug;
import android.widget.Button;

public class ButtonPreference extends Preference {

    private String mButtonText;
    private View.OnClickListener mButtonOnClickListener;
    private int mButtonVisibility = -1;

    public ButtonPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWidgetLayoutResource(R.layout.button_preference);
    }

    public ButtonPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWidgetLayoutResource(R.layout.button_preference);
    }

    public ButtonPreference(Context context) {
        super(context);
        setWidgetLayoutResource(R.layout.button_preference);
    }

    public String getButtonText()  {
        return mButtonText;
    }

    public View.OnClickListener getButtonOnClickListener() {
        return mButtonOnClickListener;
    }

    public void setButtonText(String text)  {
        mButtonText = text;
        notifyChanged();
    }

    public void setButtonOnClickListener(View.OnClickListener l)  {
        mButtonOnClickListener = l;
        notifyChanged();
    }

    @ViewDebug.ExportedProperty(
            mapping = {@ViewDebug.IntToString(
                    from = 0,
                    to = "VISIBLE"
            ), @ViewDebug.IntToString(
                    from = 4,
                    to = "INVISIBLE"
            ), @ViewDebug.IntToString(
                    from = 8,
                    to = "GONE"
            )}
    )
    public void setButtonVisibility(int visibility)  {
        mButtonVisibility = visibility;
        notifyChanged();
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        View buttonView = view.findViewById(R.id.button);
        if (buttonView != null && buttonView instanceof Button) {
            if (mButtonText != null) {
                buttonView.setVisibility(View.VISIBLE);
                ((Button) buttonView).setText(mButtonText);
            } else
                buttonView.setVisibility(View.GONE);
            if (mButtonOnClickListener != null)
                buttonView.setOnClickListener(mButtonOnClickListener);
            if (mButtonVisibility != -1)
                switch(mButtonVisibility) {
                    case View.VISIBLE:
                        buttonView.setVisibility(View.VISIBLE);
                        break;
                    case View.INVISIBLE:
                        buttonView.setVisibility(View.VISIBLE);
                        break;
                    case View.GONE:
                        buttonView.setVisibility(View.VISIBLE);
                        break;
                    default:
                        buttonView.setVisibility(View.GONE);
                }
        }
    }
}
